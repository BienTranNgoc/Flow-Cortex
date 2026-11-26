package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.phone.PhoneChallenge.Purpose;
import com.flowcortex.user.phone.PhoneVerificationService.SentCode;
import com.flowcortex.user.phone.SmsMessage.Kind;
import com.flowcortex.user.phone.SmsSendFailure.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(OutputCaptureExtension.class)
class PhoneVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    private static final String PHONE = "+84912345678";
    private static final PhoneNumberHasher HASHER =
            new PhoneNumberHasher(new PhoneHashProperties("phone-hash-secret-for-unit-tests-0123456789"), true);
    private static final String PHONE_HASH = HASHER.hash(PHONE);
    private static final String IP = "203.0.113.7";
    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    private static final String GLOBAL_LOCK = "phone-send-global";
    private static final UUID ATTEMPT_ID = UUID.fromString("7d1b5a0e-3c1f-4f55-9a43-0a2f5d9b6c11");
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);
    private static final PhoneSendLimits LIMITS = new PhoneSendLimits(List.of("84"), 10, 3,
            new PhoneSendLimits.IpLimit(30, Duration.ofHours(1)), 20, 2000);

    private final PhoneChallengeRepository challenges = mock(PhoneChallengeRepository.class);
    private final UserPhoneRepository phones = mock(UserPhoneRepository.class);
    private final SmsSender smsSender = mock(SmsSender.class);
    private final PhoneSendAttemptStore attempts = mock(PhoneSendAttemptStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PhoneSendIpLimiter ipLimiter = spy(new PhoneSendIpLimiter(new RateLimiters(
            List.of(new PhoneRateLimits().phoneSendIp(LIMITS)), new InMemoryRateLimitStore(),
            new RateLimitTelemetry(new SimpleMeterRegistry(), Clock.systemUTC()))));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void everyRecordedAttemptGetsTheSameId() {
        when(attempts.record(any(), any(), any(), any(), any())).thenReturn(ATTEMPT_ID);
        when(smsSender.send(any(SmsMessage.class))).thenReturn(SmsDelivery.ACCEPTED);
    }

    @Test
    void aSendStoresOnlyAHashOfTheCodeAndLogsNeitherTheCodeNorTheFullNumber(CapturedOutput output) {
        when(challenges.findSendTimesSince(eq(PHONE), any())).thenReturn(List.of());

        SentCode sent = service(NOW).sendCode(userId, "0912345678", IP);

        ArgumentCaptor<SmsMessage> message = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsSender).send(message.capture());
        assertThat(message.getValue().kind()).isEqualTo(Kind.OTP);
        assertThat(message.getValue().phoneE164()).isEqualTo(PHONE);
        assertThat(message.getValue().code()).matches(SIX_DIGITS);
        assertThat(message.getValue().requestId()).isEqualTo(ATTEMPT_ID.toString());
        String code = message.getValue().code();

        ArgumentCaptor<PhoneChallenge> saved = ArgumentCaptor.forClass(PhoneChallenge.class);
        InOrder order = inOrder(jdbc, challenges, attempts);
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq("phone-send-user:" + userId));
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq(PHONE));
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq(GLOBAL_LOCK));
        order.verify(attempts).record(userId, PHONE_HASH, IP, "sent", NOW);
        order.verify(challenges).invalidateOpen(userId, NOW);
        order.verify(challenges).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).isNotEqualTo(code);
        assertThat(encoder.matches(code, saved.getValue().getCodeHash())).isTrue();
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));

        assertThat(sent.maskedPhone()).isEqualTo("+84 9•• ••• 678");
        assertThat(sent.resendAfterSeconds()).isEqualTo(60);
        assertThat(output.getAll()).contains("phone=***678", "outcome=sent").doesNotContain(code)
                .doesNotContain("912345678");
    }

    @Test
    void anUnavailableCarrierAnswers503AndGivesTheAttemptBack(CapturedOutput output) {
        when(smsSender.send(any(SmsMessage.class))).thenThrow(new SmsSendFailure(Reason.UNAVAILABLE));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(SmsUnavailableException.class, refused -> {
                    assertThat(refused.getBody().getProperties())
                            .containsEntry("code", "SMS_UNAVAILABLE")
                            .containsEntry("retryAfterSeconds", 60L);
                    assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
                    assertThat(english(refused)).isEqualTo("We could not send the code right now. "
                            + "Try again in a minute.");
                });

        ArgumentCaptor<PhoneChallenge> saved = ArgumentCaptor.forClass(PhoneChallenge.class);
        verify(challenges).save(saved.capture());
        InOrder order = inOrder(transactions, attempts, challenges);
        order.verify(transactions).commit(any());
        order.verify(attempts).markOutcome(ATTEMPT_ID, "sms_unavailable");
        order.verify(challenges).deleteById(saved.getValue().getId());
        order.verify(transactions).commit(any());
        assertThat(meters.counter("phone_otp.send", "outcome", "sms_unavailable").count()).isEqualTo(1);
        assertThat(output.getAll()).contains("phone=***678", "outcome=sms_unavailable").doesNotContain("912345678");
    }

    @Test
    void aRefusedNumberAnswers422PhoneUndeliverable() {
        when(smsSender.send(any(SmsMessage.class))).thenThrow(new SmsSendFailure(Reason.NUMBER_REJECTED));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("PHONE_UNDELIVERABLE");
                    assertThat(english(refused.getDetail()))
                            .isEqualTo("This number cannot receive SMS. Check it or use another number.");
                    assertThat(LOCALE.getMessage(refused.getDetail(), Locale.forLanguageTag("vi")))
                            .isEqualTo("Số này không nhận được tin nhắn SMS. Vui lòng kiểm tra lại hoặc dùng số khác.");
                });

        verify(attempts).markOutcome(ATTEMPT_ID, "phone_undeliverable");
        verify(challenges).deleteById(any());
        assertThat(meters.counter("phone_otp.send", "outcome", "phone_undeliverable").count()).isEqualTo(1);
    }

    @Test
    void theOutageMessageIsInVietnamese() {
        when(smsSender.send(any(SmsMessage.class))).thenThrow(new SmsSendFailure(Reason.UNAVAILABLE));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(SmsUnavailableException.class, refused -> assertThat(LOCALE.getMessage(
                        refused.getDetailMessageCode(), refused.getDetailMessageArguments(),
                        Locale.forLanguageTag("vi"))).isEqualTo("Hiện chưa gửi được mã. Vui lòng thử lại sau một phút."));
    }

    @Test
    void anUnconfirmedSendIsAnsweredAsSent() {
        when(smsSender.send(any(SmsMessage.class))).thenReturn(SmsDelivery.UNCONFIRMED);

        SentCode sent = service(NOW).sendCode(userId, "0912345678", IP);

        assertThat(sent.resendAfterSeconds()).isEqualTo(60);
        verify(attempts, never()).markOutcome(any(), any());
        verify(challenges, never()).deleteById(any());
        assertThat(meters.counter("phone_otp.send", "outcome", "sent").count()).isEqualTo(1);
    }

    @Test
    void theCarrierIsCalledAfterTheDecisionCommits() {
        service(NOW).sendCode(userId, "0912345678", IP);

        InOrder order = inOrder(encoder, jdbc, attempts, challenges, transactions, smsSender);
        order.verify(encoder).encode(any());
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq("phone-send-user:" + userId));
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq(PHONE));
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq(GLOBAL_LOCK));
        order.verify(attempts).record(userId, PHONE_HASH, IP, "sent", NOW);
        order.verify(challenges).save(any());
        order.verify(transactions).commit(any());
        order.verify(smsSender).send(any(SmsMessage.class));
        verify(ipLimiter).tryAcquire(IP);
    }

    @Test
    void aRefusedSendNeverTakesTheGlobalLock() {
        when(challenges.findSendTimesByUserSince(eq(userId), eq(PHONE), any())).thenReturn(List.of(NOW.minusSeconds(59)));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOf(OtpRateLimitedException.class);

        verify(jdbc, never()).query(eq(LOCK), any(RowCallbackHandler.class), eq(GLOBAL_LOCK));
        verify(ipLimiter).tryAcquire(IP);
    }

    @Test
    void theBudgetIsReadAgainUnderTheGlobalLockAndRefusesTheSendThatNowFillsIt() {
        when(attempts.nthNewestSendSince(any(), eq(2000))).thenReturn(Optional.empty(), Optional.of(NOW.minusSeconds(60)));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(OtpRateLimitedException.class, refused -> assertThat(
                        refused.getBody().getProperties()).containsEntry("code", "OTP_GLOBAL_LIMIT"));

        InOrder order = inOrder(jdbc, attempts);
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq(GLOBAL_LOCK));
        order.verify(attempts).record(userId, PHONE_HASH, IP, "global_limit", NOW);
        verify(challenges, never()).save(any());
        verify(smsSender, never()).send(any(SmsMessage.class));
        verify(ipLimiter).tryAcquire(IP);
    }

    @Test
    void anUnexpectedSenderFailureAnswers503AndGivesTheAttemptBack() {
        when(smsSender.send(any(SmsMessage.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOf(SmsUnavailableException.class);

        verify(attempts).markOutcome(ATTEMPT_ID, "sms_unavailable");
        verify(challenges).deleteById(any());
    }

    @Test
    void aFailedGiveBackStillAnswers503AndIsLogged(CapturedOutput output) {
        when(smsSender.send(any(SmsMessage.class))).thenThrow(new SmsSendFailure(Reason.UNAVAILABLE));
        doThrow(new IllegalStateException("database down")).when(attempts).markOutcome(any(), any());

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOf(SmsUnavailableException.class);

        assertThat(output.getAll()).contains("outcome=release_failed", "java.lang.IllegalStateException")
                .doesNotContain("database down");
    }

    @Test
    void aNumberVerifiedOnAnotherAccountIsAConflictRecordedAgainstTheQuotaAndNothingIsSent() {
        when(phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(PHONE, userId)).thenReturn(true);

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "+84912345678", IP))
                .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(english(refused.getDetail()))
                        .isEqualTo("This number is already verified on another account"));
        verify(attempts).record(userId, PHONE_HASH, IP, "number_taken", NOW);
        verify(smsSender, never()).send(any(SmsMessage.class));
    }

    @Test
    void aCooldownRefusalNamesItsCodeAndWhenToRetryAndIsRecorded() {
        when(challenges.findSendTimesByUserSince(eq(userId), eq(PHONE), any())).thenReturn(List.of(NOW.minusSeconds(59)));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(OtpRateLimitedException.class, refused -> {
                    assertThat(refused.getBody().getProperties())
                            .containsEntry("code", "OTP_COOLDOWN")
                            .containsEntry("retryAfterSeconds", 1L);
                    assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
                });
        verify(attempts).record(userId, PHONE_HASH, IP, "cooldown", NOW);
        verify(challenges, never()).save(any());
        verify(smsSender, never()).send(any(SmsMessage.class));
    }

    @Test
    void anotherMembersRecentSendToTheNumberDoesNotHoldThisMemberBack() {
        when(challenges.findSendTimesSince(eq(PHONE), any())).thenReturn(List.of(NOW.minusSeconds(5)));

        SentCode sent = service(NOW).sendCode(userId, "0912345678", IP);

        assertThat(sent.resendAfterSeconds()).isEqualTo(60);
        verify(smsSender).send(argThat(message -> message.phoneE164().equals(PHONE)));
    }

    @Test
    void aFourthNumberInADayIsRefusedEvenWhenItIsVerifiedElsewhere() {
        when(attempts.countedByUserSince(eq(userId), any())).thenReturn(List.of(
                new SendFacts.CountedAttempt("a", NOW.minusSeconds(3600)),
                new SendFacts.CountedAttempt("b", NOW.minusSeconds(1800)),
                new SendFacts.CountedAttempt("c", NOW.minusSeconds(600))));
        when(phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(PHONE, userId)).thenReturn(true);

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(OtpRateLimitedException.class, refused -> {
                    assertThat(refused.getBody().getProperties())
                            .containsEntry("code", "OTP_USER_NUMBER_LIMIT")
                            .containsEntry("retryAfterSeconds", 23L * 3600);
                    assertThat(english(refused))
                            .isEqualTo("You've tried too many different numbers today. Try again in 23 hours.");
                });
        verify(attempts).record(userId, PHONE_HASH, IP, "user_number_limit", NOW);
        verify(smsSender, never()).send(any(SmsMessage.class));
    }

    @Test
    void aSendRefusalAndItsWaitAreBothInVietnamese() {
        when(attempts.countedByUserSince(eq(userId), any())).thenReturn(List.of(
                new SendFacts.CountedAttempt("a", NOW.minusSeconds(3600)),
                new SendFacts.CountedAttempt("b", NOW.minusSeconds(1800)),
                new SendFacts.CountedAttempt("c", NOW.minusSeconds(600))));

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOfSatisfying(OtpRateLimitedException.class, refused -> assertThat(LOCALE.getMessage(
                        refused.getDetailMessageCode(), refused.getDetailMessageArguments(), Locale.forLanguageTag("vi")))
                        .isEqualTo("Bạn đã thử quá nhiều số điện thoại khác nhau hôm nay. Hãy thử lại sau 23 giờ."));
    }

    @Test
    void startupFailsWhenTheFeatureIsOnAndNoSmsSenderIsConfigured() {
        assertThatThrownBy(() -> service(NOW, null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.sms.sender");
    }

    @Test
    void withTheFeatureOffNoSmsSenderIsNeeded() {
        assertThat(service(NOW, null, false)).isNotNull();
    }

    @Test
    void theRightCodeConsumesTheChallengeAndRecordsTheVerifiedNumber(CapturedOutput output) {
        PhoneChallenge challenge = challenge("482913", NOW.minusSeconds(30));
        when(challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, Purpose.VERIFY))
                .thenReturn(Optional.of(challenge));
        when(challenges.consume(challenge.getId(), NOW)).thenReturn(1);
        when(phones.findById(userId)).thenReturn(Optional.empty());

        service(NOW).verify(userId, "482913");

        ArgumentCaptor<UserPhone> saved = ArgumentCaptor.forClass(UserPhone.class);
        verify(phones).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPhoneE164()).isEqualTo(PHONE);
        assertThat(saved.getValue().getVerifiedAt()).isEqualTo(NOW);
        assertThat(output.getAll()).contains("outcome=verified").doesNotContain("482913");
    }

    @Test
    void theFourthWrongCodeLeavesOneTryAndTheFifthLocksTheCode() {
        PhoneChallenge challenge = challenge("482913", NOW.minusSeconds(30));
        when(challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, Purpose.VERIFY))
                .thenReturn(Optional.of(challenge));
        when(challenges.countWrongAttempt(challenge.getId())).thenReturn(1);
        when(challenges.findAttempts(challenge.getId())).thenReturn(4, 5);

        assertThatThrownBy(() -> service(NOW).verify(userId, "000000"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("OTP_INVALID");
                    assertThat(english(refused.getDetail())).isEqualTo("Wrong code. 1 try left.");
                });
        assertThatThrownBy(() -> service(NOW).verify(userId, "000000"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("OTP_LOCKED");
                    assertThat(english(refused.getDetail())).isEqualTo("Too many wrong codes. Request a new one.");
                });
        verify(challenges).invalidate(challenge.getId(), NOW);
    }

    @Test
    void anEarlierCodeIsNoLongerValidAndDoesNotCountAsAWrongEntry() {
        PhoneChallenge current = challenge("482913", NOW.minusSeconds(10));
        PhoneChallenge earlier = challenge("222222", NOW.minusSeconds(70));
        when(challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, Purpose.VERIFY))
                .thenReturn(Optional.of(current));
        when(challenges.findTop5ByUserIdAndPurposeAndIdNotOrderByCreatedAtDesc(userId, Purpose.VERIFY,
                current.getId())).thenReturn(List.of(earlier));

        assertThatThrownBy(() -> service(NOW).verify(userId, "222222"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("OTP_INVALID");
                    assertThat(english(refused.getDetail()))
                            .isEqualTo("This code is no longer valid. Request a new one.");
                });
        verify(challenges, never()).countWrongAttempt(any());
    }

    @Test
    void theRightCodeAfterFiveMinutesHasExpired() {
        PhoneChallenge challenge = challenge("482913", NOW);
        when(challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, Purpose.VERIFY))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service(NOW.plusSeconds(301)).verify(userId, "482913"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("OTP_EXPIRED"));
        verify(challenges, never()).consume(any(), any());
    }

    @Test
    void aMemberWithAVerifiedNumberIsRefusedOnSendBeforeAnyQuotaIsReadOrAttemptRecorded() {
        holdsAVerifiedNumber();

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0987654321", IP))
                .isInstanceOfSatisfying(PhoneConflictException.class, refused -> {
                    assertThat(refused.getBody().getProperties()).containsEntry("code", "PHONE_ALREADY_VERIFIED");
                    assertThat(english(refused)).isEqualTo("Your phone number is already verified. "
                            + "To use another number, change it from your profile.");
                });
        verifyNoInteractions(attempts, smsSender, jdbc, challenges);
        assertThat(meters.counter("phone_otp.send", "outcome", "already_verified").count()).isEqualTo(1);
    }

    @Test
    void aMemberWithAVerifiedNumberIsRefusedOnVerify() {
        holdsAVerifiedNumber();

        assertThatThrownBy(() -> service(NOW).verify(userId, "482913"))
                .isInstanceOfSatisfying(PhoneConflictException.class, refused -> assertThat(
                        refused.getBody().getProperties()).containsEntry("code", "PHONE_ALREADY_VERIFIED"));
        verify(challenges, never()).consume(any(), any());
        verify(challenges, never()).countWrongAttempt(any());
        verify(phones, never()).saveAndFlush(any());
        assertThat(meters.counter("phone_otp.verify", "outcome", "already_verified").count()).isEqualTo(1);
    }

    @Test
    void thePlainVerifyReadsOnlyVerifyPurposeCodes() {
        PhoneChallenge plain = challenge("482913", NOW.minusSeconds(60));
        PhoneChallenge change = new PhoneChallenge(UUID.randomUUID(), userId, "+84987654321",
                encoder.encode("111111"), NOW.plusSeconds(290), NOW.minusSeconds(10), Purpose.CHANGE);
        when(challenges.findFirstByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                userId, Purpose.VERIFY)).thenReturn(Optional.of(plain));
        when(challenges.findFirstByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                userId, Purpose.CHANGE)).thenReturn(Optional.of(change));
        when(challenges.countWrongAttempt(plain.getId())).thenReturn(1);
        when(challenges.findAttempts(plain.getId())).thenReturn(1);
        when(challenges.consume(plain.getId(), NOW)).thenReturn(1);

        assertThatThrownBy(() -> service(NOW).verify(userId, "111111"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("OTP_INVALID"));
        service(NOW).verify(userId, "482913");

        verify(challenges, never()).consume(eq(change.getId()), any());
        verify(challenges, never()).countWrongAttempt(change.getId());
        verify(challenges).consume(plain.getId(), NOW);
    }

    @Test
    void everySendAndCheckOutcomeIsCountedByItsName() {
        when(challenges.findSendTimesByUserSince(eq(userId), eq(PHONE), any())).thenReturn(List.of(NOW.minusSeconds(59)));
        assertThatThrownBy(() -> service(NOW).sendCode(userId, "0912345678", IP))
                .isInstanceOf(OtpRateLimitedException.class);
        PhoneChallenge challenge = challenge("482913", NOW.minusSeconds(30));
        when(challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, Purpose.VERIFY))
                .thenReturn(Optional.of(challenge));
        when(challenges.countWrongAttempt(challenge.getId())).thenReturn(1);
        when(challenges.findAttempts(challenge.getId())).thenReturn(1);
        assertThatThrownBy(() -> service(NOW).verify(userId, "000000"))
                .isInstanceOf(UnprocessableEntityException.class);

        assertThat(meters.counter("phone_otp.send", "outcome", "cooldown").count()).isEqualTo(1);
        assertThat(meters.counter("phone_otp.verify", "outcome", "wrong").count()).isEqualTo(1);
    }

    private void holdsAVerifiedNumber() {
        when(phones.findById(userId)).thenReturn(Optional.of(new UserPhone(userId, PHONE, NOW.minusSeconds(86_400))));
    }

    private PhoneVerificationService service(Instant now) {
        return service(now, smsSender, true);
    }

    @SuppressWarnings("unchecked")
    private PhoneVerificationService service(Instant now, SmsSender sender, boolean enabled) {
        ObjectProvider<SmsSender> senders = mock(ObjectProvider.class);
        when(senders.getIfAvailable()).thenReturn(sender);
        return new PhoneVerificationService(challenges, phones, senders, enabled, attempts, HASHER, ipLimiter,
                LIMITS, encoder, jdbc, transactions, Clock.fixed(now, ZoneOffset.UTC),
                LOCALE.getBean(RequestLocale.class), new PhoneTelemetry(meters));
    }

    private static String english(MessageSourceResolvable message) {
        return LOCALE.getMessage(message, Locale.ENGLISH);
    }

    private static String english(LocalizedStatusException refused) {
        return LOCALE.getMessage(refused.getDetailMessageCode(), refused.getDetailMessageArguments(), Locale.ENGLISH);
    }

    private PhoneChallenge challenge(String code, Instant sentAt) {
        return new PhoneChallenge(UUID.randomUUID(), userId, PHONE, encoder.encode(code), sentAt.plusSeconds(300), sentAt);
    }
}
