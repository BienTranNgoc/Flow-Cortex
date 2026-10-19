package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.phone.PhoneVerificationService.SentCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(OutputCaptureExtension.class)
class PhoneVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    private static final String PHONE = "+84912345678";
    private static final PhoneNumberHasher HASHER =
            new PhoneNumberHasher(new PhoneHashProperties("phone-hash-secret-for-unit-tests-0123456789"), true);
    private static final String PHONE_HASH = HASHER.hash(PHONE);
    private static final String IP = "203.0.113.7";
    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    private static final Pattern BODY = Pattern.compile("^Your Flow-Cortex code is (\\d{6})\\. It expires in 5 minutes\\.$");
    private static final Pattern VIETNAMESE_BODY =
            Pattern.compile("^Mã xác minh Flow-Cortex của bạn là (\\d{6})\\. Mã hết hạn sau 5 phút\\.$");
    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);
    private static final PhoneSendLimits LIMITS = new PhoneSendLimits(List.of("84"), 10, 3,
            new PhoneSendLimits.IpLimit(30, Duration.ofHours(1)), 20, 2000);

    private final PhoneChallengeRepository challenges = mock(PhoneChallengeRepository.class);
    private final UserPhoneRepository phones = mock(UserPhoneRepository.class);
    private final SmsSender smsSender = mock(SmsSender.class);
    private final PhoneSendAttemptStore attempts = mock(PhoneSendAttemptStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UUID userId = UUID.randomUUID();

    @Test
    void aSendStoresOnlyAHashOfTheCodeAndLogsNeitherTheCodeNorTheFullNumber(CapturedOutput output) {
        when(challenges.findSendTimesSince(eq(PHONE), any())).thenReturn(List.of());

        SentCode sent = service(NOW).sendCode(userId, "0912345678", IP);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq(PHONE), body.capture());
        Matcher matcher = BODY.matcher(body.getValue());
        assertThat(matcher.matches()).isTrue();
        String code = matcher.group(1);

        ArgumentCaptor<PhoneChallenge> saved = ArgumentCaptor.forClass(PhoneChallenge.class);
        InOrder order = inOrder(jdbc, challenges, attempts);
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq("phone-send-user:" + userId));
        order.verify(jdbc).query(eq(LOCK), any(RowCallbackHandler.class), eq(PHONE));
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
    void aRequestInVietnameseGetsTheCodeTextInVietnamese() {
        when(challenges.findSendTimesSince(eq(PHONE), any())).thenReturn(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            service(NOW).sendCode(userId, "0912345678", IP);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq(PHONE), body.capture());
        assertThat(body.getValue()).matches(VIETNAMESE_BODY);
    }

    @Test
    void aNumberVerifiedOnAnotherAccountIsAConflictRecordedAgainstTheQuotaAndNothingIsSent() {
        when(phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(PHONE, userId)).thenReturn(true);

        assertThatThrownBy(() -> service(NOW).sendCode(userId, "+84912345678", IP))
                .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(english(refused.getDetail()))
                        .isEqualTo("This number is already verified on another account"));
        verify(attempts).record(userId, PHONE_HASH, IP, "number_taken", NOW);
        verify(smsSender, never()).send(anyString(), anyString());
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
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void anotherMembersRecentSendToTheNumberDoesNotHoldThisMemberBack() {
        when(challenges.findSendTimesSince(eq(PHONE), any())).thenReturn(List.of(NOW.minusSeconds(5)));

        SentCode sent = service(NOW).sendCode(userId, "0912345678", IP);

        assertThat(sent.resendAfterSeconds()).isEqualTo(60);
        verify(smsSender).send(eq(PHONE), anyString());
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
        verify(smsSender, never()).send(anyString(), anyString());
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
        when(challenges.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(challenge));
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
        when(challenges.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(challenge));
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
        when(challenges.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(current));
        when(challenges.findTop5ByUserIdAndIdNotOrderByCreatedAtDesc(userId, current.getId()))
                .thenReturn(List.of(earlier));

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
        when(challenges.findFirstByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service(NOW.plusSeconds(301)).verify(userId, "482913"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("OTP_EXPIRED"));
        verify(challenges, never()).consume(any(), any());
    }

    private PhoneVerificationService service(Instant now) {
        return service(now, smsSender, true);
    }

    @SuppressWarnings("unchecked")
    private PhoneVerificationService service(Instant now, SmsSender sender, boolean enabled) {
        ObjectProvider<SmsSender> senders = mock(ObjectProvider.class);
        when(senders.getIfAvailable()).thenReturn(sender);
        return new PhoneVerificationService(challenges, phones, senders, enabled, attempts, HASHER,
                new PhoneSendIpLimiter(LIMITS), LIMITS, encoder, jdbc, mock(PlatformTransactionManager.class),
                Clock.fixed(now, ZoneOffset.UTC), LOCALE, LOCALE.getBean(RequestLocale.class));
    }

    private static String english(MessageSourceResolvable message) {
        return LOCALE.getMessage(message, Locale.ENGLISH);
    }

    private static String english(OtpRateLimitedException refused) {
        return LOCALE.getMessage(refused.getDetailMessageCode(), refused.getDetailMessageArguments(), Locale.ENGLISH);
    }

    private PhoneChallenge challenge(String code, Instant sentAt) {
        return new PhoneChallenge(UUID.randomUUID(), userId, PHONE, encoder.encode(code), sentAt.plusSeconds(300), sentAt);
    }
}
