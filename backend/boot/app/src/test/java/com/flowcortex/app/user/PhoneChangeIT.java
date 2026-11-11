package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.app.user.CapturingMailSender.CapturedMail;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

/**
 * Changing a verified number over real HTTP and the real database: the step-up proof, the change
 * code, the swap and its notices, and the undo window. The clock moves forward only; every case uses
 * its own accounts and its own numbers.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "flowcortex.phone-verification.enabled=true",
        "flowcortex.sms.sender=outbox"})
@ExtendWith(OutputCaptureExtension.class)
@Import(PhoneChangeIT.MailAndClock.class)
class PhoneChangeIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "password123";
    private static final Pattern CODE_IN_BODY =
            Pattern.compile("^Your Flow-Cortex code is (\\d{6})\\. It expires in 5 minutes\\.$");
    private static final String CHANGED_NOTICE = "Your Flow-Cortex phone number was changed. If this was not you, "
            + "undo it within 24 hours in your profile or contact support.";
    private static final String REVERTED_NOTICE = "Your Flow-Cortex phone number change was undone. If this was not "
            + "you, contact support.";
    private static final String NO_LONGER_VALID = "This code is no longer valid. Request a new one.";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovableClock clock;

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private StepUpProofs stepUpProofs;

    private String clientIp;

    record ProblemView(int status, String detail, String code, Long retryAfterSeconds, Map<String, String> errors) {
    }

    record SendCodeView(String maskedPhone, Long resendAfterSeconds, Instant expiresAt) {
    }

    record PhoneView(String phone, Boolean verified, String maskedPhone, Instant cancelAvailableUntil) {
    }

    record StepUpView(String stepUpProof, Instant expiresAt) {
    }

    record ChangeStartBody(String phone, String stepUpProof) {
    }

    record CodeBody(String code) {
    }

    record SendCodeBody(String phone) {
    }

    private record Member(UUID id, String email, String token) {
    }

    // The default Apache client honours Retry-After on a 429 and would sleep through it.
    @BeforeEach
    void answerRefusalsWithoutRetrying() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    // The per-IP bucket runs on the real clock across the whole class; one IP per case keeps cases apart.
    @BeforeEach
    void sendFromAnIpOfItsOwn() {
        clientIp = "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aChangeCodeGoesToTheNewNumberAndTheOldStaysVerified() {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("start", old);
        long before = outboxRows();

        ResponseEntity<SendCodeView> sent = changeStart(member, next, proofFor(member), SendCodeView.class);

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().maskedPhone()).isEqualTo(masked(next));
        assertThat(sent.getBody().resendAfterSeconds()).isEqualTo(60L);
        assertThat(sent.getBody().expiresAt()).isAfter(clock.instant());
        assertThat(outboxRows()).isEqualTo(before + 1);
        assertThat(latestCodeFor(next)).matches("\\d{6}");
        PhoneView view = phone(member);
        assertThat(view.phone()).isEqualTo(old);
        assertThat(view.verified()).isTrue();
        assertThat(view.cancelAvailableUntil()).isNull();
    }

    @Test
    void aMissingOrUsedProofIs403StepUpRequiredAndSendsNothing() {
        Member member = verifiedMember("proof", randomNumber());
        String used = proofFor(member);
        assertThat(changeStart(member, randomNumber(), used, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String expired = proofFor(member);
        clock.advance(Duration.ofSeconds(301));
        String neverMinted = "never-minted-proof";
        List<String> refused = new ArrayList<>();
        long before = outboxRows();

        for (String proof : List.of(used, expired, neverMinted)) {
            ResponseEntity<ProblemView> response = changeStart(member, randomNumber(), proof, ProblemView.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            refused.add(response.getBody().code());
        }

        assertThat(refused).containsOnly("STEP_UP_REQUIRED");
        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    void aPatCreateProofIsRefusedAndSpent() {
        Member member = verifiedMember("other-purpose", randomNumber());
        String proof = proofFor(member, "PAT_CREATE");
        long before = outboxRows();

        ResponseEntity<ProblemView> response = changeStart(member, randomNumber(), proof, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(outboxRows()).isEqualTo(before);
        assertThatThrownBy(() -> stepUpProofs.consume(member.id(), StepUpPurpose.PAT_CREATE, proof))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void aMalformedNumberIs400AndLeavesTheProofUsable() {
        Member member = verifiedMember("malformed", randomNumber());
        String proof = proofFor(member);
        long before = outboxRows();

        ResponseEntity<ProblemView> malformed = changeStart(member, "12345", proof, ProblemView.class);
        ResponseEntity<ProblemView> blank = changeStart(member, randomNumber(), " ", ProblemView.class);

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody().errors()).containsEntry("phone", "Enter a valid Vietnamese mobile number");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().errors()).containsKey("stepUpProof");
        assertThat(outboxRows()).isEqualTo(before);
        assertThat(changeStart(member, randomNumber(), proof, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void noVerifiedNumberIs409() {
        Member member = newMember("unverified");
        String proof = proofFor(member);
        long before = outboxRows();

        ResponseEntity<ProblemView> response = changeStart(member, randomNumber(), proof, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PHONE_NOT_VERIFIED");
        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    void aGoogleOnlyMemberGets409StepUpPasswordRequiredAtTheStepUp() {
        Member member = verifiedMember("google", randomNumber());
        jdbc.update("UPDATE app_user SET has_password = false, password_hash = '' WHERE id = ?", member.id());

        ResponseEntity<ProblemView> response = stepUp(member, "anything", "PHONE_CHANGE", ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("STEP_UP_PASSWORD_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step_up_proof WHERE user_id = ?", Integer.class,
                member.id())).isZero();
    }

    @Test
    void theSameNumberIs422AndSpendsTheProof() {
        String old = randomNumber();
        Member member = verifiedMember("same", old);
        String proof = proofFor(member);
        long before = outboxRows();

        ResponseEntity<ProblemView> same = changeStart(member, old, proof, ProblemView.class);
        ResponseEntity<ProblemView> again = changeStart(member, randomNumber(), proof, ProblemView.class);

        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(same.getBody().code()).isEqualTo("PHONE_SAME_NUMBER");
        assertThat(outboxRows()).isEqualTo(before);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cooldownStillApplies() {
        Member member = verifiedMember("cooldown", randomNumber());
        String next = randomNumber();
        assertThat(changeStart(member, next, proofFor(member), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.advance(Duration.ofSeconds(30));

        ResponseEntity<ProblemView> again = changeStart(member, next, proofFor(member), ProblemView.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(again.getBody().code()).isEqualTo("OTP_COOLDOWN");
        assertThat(again.getBody().retryAfterSeconds()).isEqualTo(30L);
        assertThat(outboxRowsFor(next)).isEqualTo(1);
    }

    @Test
    void theRightChangeCodeSwapsNumbersNotifiesTheOldNumberMailsAndAudits() {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("swap", old);
        changeStart(member, next, proofFor(member), String.class);
        clock.advance(Duration.ofSeconds(40));
        Instant before = clock.instant();

        ResponseEntity<PhoneView> verified = changeVerify(member, latestCodeFor(next), PhoneView.class);

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().phone()).isEqualTo(next);
        assertThat(verified.getBody().verified()).isTrue();
        assertThat(verified.getBody().cancelAvailableUntil())
                .isBetween(before.plus(Duration.ofHours(24)), clock.instant().plus(Duration.ofHours(24)));
        assertThat(phone(member)).isEqualTo(verified.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_phone WHERE phone_e164 = ?", Integer.class, old))
                .isZero();
        assertThat(bodiesTo(old)).contains(CHANGED_NOTICE);
        List<CapturedMail> mails = mail.sentTo(member.email());
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().subject()).isEqualTo("Your Flow-Cortex phone number was changed");
        assertThat(mails.getFirst().body()).contains(masked(next)).doesNotContain(next);
        String audit = auditMetadata(member.id(), "PHONE_CHANGED");
        assertThat(audit).contains(masked(old), masked(next)).doesNotContain(old).doesNotContain(next);
    }

    @Test
    void aFailingNoticeSenderDoesNotFailTheChange(CapturedOutput output) {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("notice-down", old);
        changeStart(member, next, proofFor(member), String.class);
        String code = latestCodeFor(next);
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_phone_notice() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'notice sender down'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER reject_phone_notice BEFORE INSERT ON sms_outbox FOR EACH ROW WHEN "
                + "(NEW.phone_e164 = '" + old + "' AND NEW.body LIKE 'Your Flow-Cortex phone number was changed%') "
                + "EXECUTE FUNCTION reject_phone_notice()");
        ResponseEntity<PhoneView> verified;
        try {
            verified = changeVerify(member, code, PhoneView.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_phone_notice ON sms_outbox");
            jdbc.execute("DROP FUNCTION reject_phone_notice()");
        }

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().phone()).isEqualTo(next);
        assertThat(bodiesTo(old)).doesNotContain(CHANGED_NOTICE);
        assertThat(output.getAll()).contains("channel=sms").doesNotContain(old).doesNotContain(next);
        assertThat(mail.sentTo(member.email())).hasSize(1);
    }

    @Test
    void aPlainCodeIsNotAcceptedByChangeVerifyAndViceVersa() {
        Member unverified = newMember("plain-on-change");
        String plain = randomNumber();
        assertThat(sendPlainCode(unverified, plain).getStatusCode()).isEqualTo(HttpStatus.OK);
        String plainCode = latestCodeFor(plain);

        ResponseEntity<ProblemView> onChange = changeVerify(unverified, plainCode, ProblemView.class);

        assertThat(onChange.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(onChange.getBody().code()).isEqualTo("PHONE_NO_PENDING_CHANGE");
        assertThat(post("/api/v1/me/phone/verify", unverified.token(), new CodeBody(plainCode), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        String old = randomNumber();
        String next = randomNumber();
        Member verified = verifiedMember("change-on-plain", old);
        changeStart(verified, next, proofFor(verified), String.class);

        ResponseEntity<ProblemView> onPlain = post("/api/v1/me/phone/verify", verified.token(),
                new CodeBody(latestCodeFor(next)), ProblemView.class);

        assertThat(onPlain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(onPlain.getBody().code()).isEqualTo("PHONE_ALREADY_VERIFIED");
        assertThat(phone(verified).phone()).isEqualTo(old);
    }

    @Test
    void aWrongChangeCodeCountsAndTheOldNumberStaysVerified() {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("wrong", old);
        changeStart(member, next, proofFor(member), String.class);
        String code = latestCodeFor(next);
        String wrong = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);

        ResponseEntity<ProblemView> response = changeVerify(member, wrong, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("OTP_INVALID");
        assertThat(response.getBody().detail()).isEqualTo("Wrong code. 4 tries left.");
        assertThat(phone(member).phone()).isEqualTo(old);
        assertThat(changeVerify(member, code, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNewNumberTakenMeanwhileIs409AndNothingChanges() {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("taken", old);
        changeStart(member, next, proofFor(member), String.class);
        String code = latestCodeFor(next);
        verifiedMember("taker", next);

        ResponseEntity<ProblemView> response = changeVerify(member, code, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).isEqualTo("This number is already verified on another account");
        PhoneView view = phone(member);
        assertThat(view.phone()).isEqualTo(old);
        assertThat(view.cancelAvailableUntil()).isNull();
    }

    @Test
    void twoConcurrentChangeVerifiesSucceedOnce() throws Exception {
        String next = randomNumber();
        Member member = verifiedMember("race", randomNumber());
        changeStart(member, next, proofFor(member), String.class);
        String code = latestCodeFor(next);

        List<ResponseEntity<String>> answers = runTogether(
                () -> changeVerify(member, code, String.class),
                () -> changeVerify(member, code, String.class));

        assertThat(answers).extracting(answer -> answer.getStatusCode().value()).containsExactlyInAnyOrder(200, 422);
        ResponseEntity<String> refused = answers.stream()
                .filter(answer -> answer.getStatusCode() != HttpStatus.OK).findFirst().orElseThrow();
        assertThat(refused.getBody()).contains(NO_LONGER_VALID);
        assertThat(phone(member).phone()).isEqualTo(next);
    }

    @Test
    void cancelInsideTheWindowRestoresTheOldNumberAndNotifiesBoth() {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("undo", old);
        changeTo(member, next);
        clock.advance(Duration.ofHours(3));

        ResponseEntity<PhoneView> cancelled = changeCancel(member, PhoneView.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().phone()).isEqualTo(old);
        assertThat(cancelled.getBody().verified()).isTrue();
        assertThat(cancelled.getBody().cancelAvailableUntil()).isNull();
        assertThat(phone(member)).isEqualTo(cancelled.getBody());
        assertThat(bodiesTo(old)).containsOnlyOnce(REVERTED_NOTICE);
        assertThat(bodiesTo(next)).containsOnlyOnce(REVERTED_NOTICE);
        assertThat(auditMetadata(member.id(), "PHONE_CHANGE_REVERTED")).contains(masked(old), masked(next));
        assertThat(changeCancel(member, ProblemView.class).getBody().code()).isEqualTo("PHONE_NO_UNDO_WINDOW");
    }

    @Test
    void cancelAt24hPlus1sIs409NoUndoWindow() {
        String next = randomNumber();
        Member member = verifiedMember("late", randomNumber());
        changeTo(member, next);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        ResponseEntity<ProblemView> response = changeCancel(member, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PHONE_NO_UNDO_WINDOW");
        assertThat(phone(member).phone()).isEqualTo(next);
    }

    @Test
    void cancelAfterTheOldNumberWasTakenIs409RevertTaken() {
        String old = randomNumber();
        String next = randomNumber();
        Member member = verifiedMember("revert-taken", old);
        changeTo(member, next);
        verifiedMember("old-number-taker", old);

        ResponseEntity<ProblemView> response = changeCancel(member, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PHONE_REVERT_TAKEN");
        assertThat(phone(member).phone()).isEqualTo(next);
    }

    @Test
    void cancelAfterTheNumberMovedOnIs409RevertConflict() {
        String next = randomNumber();
        Member member = verifiedMember("revert-conflict", randomNumber());
        changeTo(member, next);
        jdbc.update("UPDATE user_phone SET phone_e164 = ? WHERE user_id = ?", randomNumber(), member.id());

        ResponseEntity<ProblemView> response = changeCancel(member, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PHONE_REVERT_CONFLICT");
    }

    @Test
    void twoChangesUndoInReverseOrder() {
        String a = randomNumber();
        String b = randomNumber();
        String c = randomNumber();
        Member member = verifiedMember("stack", a);
        changeTo(member, b);
        changeTo(member, c);

        PhoneView first = changeCancel(member, PhoneView.class).getBody();
        PhoneView second = changeCancel(member, PhoneView.class).getBody();

        assertThat(first.phone()).isEqualTo(b);
        assertThat(first.cancelAvailableUntil()).isNotNull();
        assertThat(second.phone()).isEqualTo(a);
        assertThat(second.cancelAvailableUntil()).isNull();
        assertThat(changeCancel(member, ProblemView.class).getBody().code()).isEqualTo("PHONE_NO_UNDO_WINDOW");
    }

    @Test
    void aConcurrentCancelAndChangeVerifyLeaveAConsistentUndoStack() throws Exception {
        String a = randomNumber();
        String b = randomNumber();
        String c = randomNumber();
        Member member = verifiedMember("interleave", a);
        changeTo(member, b);
        changeStart(member, c, proofFor(member), String.class);
        String code = latestCodeFor(c);

        List<ResponseEntity<String>> answers = runTogether(
                () -> changeCancel(member, String.class),
                () -> changeVerify(member, code, String.class));

        assertThat(answers).extracting(answer -> answer.getStatusCode().value()).containsOnly(200);
        for (int undo = 0; undo < 5 && changeCancel(member, String.class).getStatusCode() == HttpStatus.OK; undo++) {
            assertThat(phone(member).phone()).isIn(a, b, c);
        }
        assertThat(phone(member).phone()).isEqualTo(a);
        assertThat(phone(member).cancelAvailableUntil()).isNull();
    }

    private void changeTo(Member member, String number) {
        assertThat(changeStart(member, number, proofFor(member), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(changeVerify(member, latestCodeFor(number), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @SafeVarargs
    private static <T> List<T> runTogether(Callable<T>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> running = new ArrayList<>();
            for (Callable<T> call : calls) {
                running.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : running) {
                answers.add(future.get(30, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private Member newMember(String prefix) {
        UserView admin = userService.createInitialAdmin(uniqueEmail(prefix + "-admin"), PASSWORD, "Admin");
        UserView user = userService.createUser(admin.id(), uniqueEmail(prefix), PASSWORD, "Member", "MEMBER");
        String token = login(user.email(), PASSWORD);
        assertThat(token).as("login of %s", user.email()).isNotNull();
        return new Member(user.id(), user.email(), token);
    }

    private Member verifiedMember(String prefix, String e164) {
        Member member = newMember(prefix);
        jdbc.update("INSERT INTO user_phone (user_id, phone_e164, verified_at) VALUES (?, ?, ?)", member.id(), e164,
                Timestamp.from(clock.instant()));
        return member;
    }

    private String proofFor(Member member) {
        return proofFor(member, "PHONE_CHANGE");
    }

    private String proofFor(Member member, String purpose) {
        ResponseEntity<StepUpView> response = stepUp(member, PASSWORD, purpose, StepUpView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().stepUpProof();
    }

    private <T> ResponseEntity<T> stepUp(Member member, String password, String purpose, Class<T> type) {
        return post("/api/v1/me/step-up", member.token(), Map.of("password", password, "purpose", purpose), type);
    }

    private <T> ResponseEntity<T> changeStart(Member member, String phone, String proof, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        headers.set("X-Forwarded-For", clientIp);
        String local = phone.startsWith("+84") ? "0" + phone.substring(3) : phone;
        return restTemplate.exchange("/api/v1/me/phone/change/start", HttpMethod.POST,
                new HttpEntity<>(new ChangeStartBody(local, proof), headers), type);
    }

    private <T> ResponseEntity<T> changeVerify(Member member, String code, Class<T> type) {
        return post("/api/v1/me/phone/change/verify", member.token(), new CodeBody(code), type);
    }

    private <T> ResponseEntity<T> changeCancel(Member member, Class<T> type) {
        return post("/api/v1/me/phone/change/cancel", member.token(), null, type);
    }

    private ResponseEntity<String> sendPlainCode(Member member, String e164) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        headers.set("X-Forwarded-For", clientIp);
        return restTemplate.exchange("/api/v1/me/phone/send-code", HttpMethod.POST,
                new HttpEntity<>(new SendCodeBody("0" + e164.substring(3)), headers), String.class);
    }

    private PhoneView phone(Member member) {
        return get("/api/v1/me/phone", member.token(), PhoneView.class).getBody();
    }

    private String latestCodeFor(String e164) {
        String body = jdbc.queryForObject(
                "SELECT body FROM sms_outbox WHERE phone_e164 = ? ORDER BY created_at DESC LIMIT 1", String.class, e164);
        Matcher matcher = CODE_IN_BODY.matcher(body);
        assertThat(matcher.matches()).as("outbox body %s", body).isTrue();
        return matcher.group(1);
    }

    private List<String> bodiesTo(String e164) {
        return jdbc.queryForList("SELECT body FROM sms_outbox WHERE phone_e164 = ?", String.class, e164);
    }

    private long outboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM sms_outbox", Long.class);
    }

    private long outboxRowsFor(String e164) {
        return jdbc.queryForObject("SELECT count(*) FROM sms_outbox WHERE phone_e164 = ?", Long.class, e164);
    }

    private String auditMetadata(UUID userId, String type) {
        List<String> rows = jdbc.queryForList(
                "SELECT metadata::text FROM auth_event WHERE user_id = ? AND type = ?", String.class, userId, type);
        assertThat(rows).as("%s events", type).hasSize(1);
        return rows.getFirst();
    }

    /** {@code +849xxxxxxxx}: a valid Vietnamese mobile number in E.164 that no other case uses. */
    private static String randomNumber() {
        return "+849" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String masked(String e164) {
        return "+84 " + e164.charAt(3) + "•• ••• " + e164.substring(e164.length() - 3);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailAndClock {

        @Bean
        CapturingMailSender capturingMailSender(ObjectProvider<ThreadPoolTaskExecutor> executor) {
            return new CapturingMailSender(executor);
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
