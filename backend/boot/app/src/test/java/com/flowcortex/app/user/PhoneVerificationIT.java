package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.phone.PhoneNumberHasher;
import com.flowcortex.user.phone.PhoneRetentionJob;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code /api/v1/me/phone} and {@code /api/v1/admin/sms-outbox} with the switch on, against the real
 * database. Time is a settable clock that only moves forward, so each case reads "seconds after its
 * own first send"; every case uses its own accounts and its own number.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "flowcortex.phone-verification.enabled=true")
@Import(PhoneVerificationIT.SettableClockConfig.class)
class PhoneVerificationIT extends AbstractUserModuleIT {

    private static final Pattern CODE_IN_BODY =
            Pattern.compile("^Your Flow-Cortex code is (\\d{6})\\. It expires in 5 minutes\\.$");
    private static final String NO_LONGER_VALID = "This code is no longer valid. Request a new one.";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private SettableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PhoneRetentionJob retentionJob;

    @Autowired
    private PhoneNumberHasher phoneHasher;

    private String clientIp;

    record ProblemView(int status, String detail, String code, Long retryAfterSeconds, Map<String, String> errors) {
    }

    record SendCodeView(String maskedPhone, Long resendAfterSeconds, Instant expiresAt) {
    }

    record PhoneView(String phone, Boolean verified, String maskedPhone) {
    }

    record OutboxEntryView(UUID id, String phone, String body, Instant createdAt) {
    }

    record SendCodeBody(String phone) {
    }

    // The default Apache client honours Retry-After on a 429 and would sleep for hours on the daily cap.
    @BeforeEach
    void answerRefusalsWithoutRetrying() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    // The per-IP bucket runs on the real clock across the whole class; one IP per case keeps cases apart.
    @BeforeEach
    void sendFromAnIpOfItsOwn() {
        clientIp = "203.0.113." + NEXT_CLIENT.getAndIncrement();
    }

    record VerifyBody(String code) {
    }

    @Test
    void anInvalidNumberIsRefusedOnThePhoneFieldAndSendsNothing() {
        String token = memberToken("phone-invalid");
        long before = outboxRows();

        ResponseEntity<ProblemView> response = sendCode(token, "12345", ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().errors()).containsEntry("phone", "Enter a valid Vietnamese mobile number");
        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    void aValidNumberGetsOneSixDigitCodeAndTheRightCodeAt299SecondsVerifiesIt() {
        String token = memberToken("phone-verify");
        String local = "0912345678";
        String e164 = "+84912345678";

        ResponseEntity<SendCodeView> sent = sendCode(token, local, SendCodeView.class);

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().maskedPhone()).isEqualTo(masked(e164));
        assertThat(sent.getBody().resendAfterSeconds()).isEqualTo(60L);
        assertThat(outboxRowsFor(e164)).isEqualTo(1);
        String code = latestCodeFor(e164);

        clock.advance(Duration.ofSeconds(299));
        assertThat(verify(token, code, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<PhoneView> phone = get("/api/v1/me/phone", token, PhoneView.class);
        assertThat(phone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(phone.getBody()).isEqualTo(new PhoneView(e164, true, masked(e164)));

        ResponseEntity<ProblemView> again = verify(token, code, ProblemView.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().code()).isEqualTo("OTP_INVALID");
        assertThat(again.getBody().detail()).isEqualTo(NO_LONGER_VALID);
    }

    @Test
    void theRightCodeAt301SecondsHasExpiredAndTheNumberStaysUnverified() {
        String token = memberToken("phone-expired");
        String e164 = sendOk(token);
        String code = latestCodeFor(e164);

        clock.advance(Duration.ofSeconds(301));
        ResponseEntity<ProblemView> response = verify(token, code, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("OTP_EXPIRED");
        assertThat(response.getBody().detail()).isEqualTo("This code has expired. Request a new one.");
        assertThat(get("/api/v1/me/phone", token, PhoneView.class).getBody())
                .isEqualTo(new PhoneView(null, false, null));
    }

    @Test
    void theFifthWrongCodeLocksTheCodeAndTheRightCodeIsRefusedAfterwards() {
        String token = memberToken("phone-locked");
        String e164 = sendOk(token);
        String code = latestCodeFor(e164);
        String wrong = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);

        List<String> details = List.of("Wrong code. 4 tries left.", "Wrong code. 3 tries left.",
                "Wrong code. 2 tries left.", "Wrong code. 1 try left.");
        for (String expected : details) {
            ResponseEntity<ProblemView> response = verify(token, wrong, ProblemView.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody().code()).isEqualTo("OTP_INVALID");
            assertThat(response.getBody().detail()).isEqualTo(expected);
        }

        ResponseEntity<ProblemView> fifth = verify(token, wrong, ProblemView.class);
        assertThat(fifth.getBody().code()).isEqualTo("OTP_LOCKED");
        assertThat(fifth.getBody().detail()).isEqualTo("Too many wrong codes. Request a new one.");

        ResponseEntity<ProblemView> right = verify(token, code, ProblemView.class);
        assertThat(right.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(right.getBody().code()).isEqualTo("OTP_LOCKED");
        assertThat(get("/api/v1/me/phone", token, PhoneView.class).getBody().verified()).isFalse();
    }

    @Test
    void aResendBeforeTheCooldownIsRefusedAndAResendKillsThePreviousCode() {
        String token = memberToken("phone-cooldown");
        String local = randomLocalNumber();
        String e164 = "+84" + local.substring(1);
        assertThat(sendCode(token, local, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String first = latestCodeFor(e164);

        clock.advance(Duration.ofSeconds(59));
        ResponseEntity<ProblemView> early = sendCode(token, local, ProblemView.class);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(early.getBody().code()).isEqualTo("OTP_COOLDOWN");
        assertThat(early.getBody().retryAfterSeconds()).isEqualTo(1L);
        assertThat(outboxRowsFor(e164)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(sendCode(token, local, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxRowsFor(e164)).isEqualTo(2);
        String second = latestCodeFor(e164);

        ResponseEntity<ProblemView> old = verify(token, first, ProblemView.class);
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(old.getBody().detail()).isEqualTo(NO_LONGER_VALID);
        assertThat(verify(token, second, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aResendExactlyAtTheCooldownIsAllowedWhenTheClockCarriesNanoseconds() {
        String token = memberToken("phone-nanos");
        String local = randomLocalNumber();
        clock.set(clock.instant().truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS).plusNanos(700));
        assertThat(sendCode(token, local, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        clock.advance(Duration.ofSeconds(60));

        assertThat(sendCode(token, local, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theLadderWaits60Then120Then300SecondsAndTheSixthCodeInAnHourIsRefused() {
        String token = memberToken("phone-ladder");
        String local = randomLocalNumber();
        String e164 = "+84" + local.substring(1);
        Instant start = clock.instant();

        for (long at : new long[] {0, 60, 180}) {
            clock.set(start.plusSeconds(at));
            assertThat(sendCode(token, local, String.class).getStatusCode()).as("send at t=%d", at)
                    .isEqualTo(HttpStatus.OK);
        }
        clock.set(start.plusSeconds(479));
        assertThat(sendCode(token, local, ProblemView.class).getBody().code()).isEqualTo("OTP_COOLDOWN");
        for (long at : new long[] {480, 780}) {
            clock.set(start.plusSeconds(at));
            assertThat(sendCode(token, local, String.class).getStatusCode()).as("send at t=%d", at)
                    .isEqualTo(HttpStatus.OK);
        }

        clock.set(start.plusSeconds(1080));
        ResponseEntity<ProblemView> sixth = sendCode(token, local, ProblemView.class);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getBody().code()).isEqualTo("OTP_HOURLY_LIMIT");
        assertThat(sixth.getBody().detail()).isEqualTo("You've requested too many codes. Try again in 42 minutes.");
        assertThat(outboxRowsFor(e164)).isEqualTo(5);
    }

    @Test
    void theEleventhCodeWithinADayIsRefused() {
        UserView member = newMember("phone-daily");
        String token = loginAs(member);
        String local = randomLocalNumber();
        String e164 = "+84" + local.substring(1);
        Instant now = clock.instant();
        for (int i = 0; i < 10; i++) {
            Instant sentAt = now.minus(Duration.ofHours(20)).plus(Duration.ofHours(2L * i));
            jdbc.update("INSERT INTO phone_challenge (id, user_id, phone_e164, code_hash, attempts, expires_at, "
                            + "invalidated_at, created_at) VALUES (?, ?, ?, 'not-a-code', 0, ?, ?, ?)",
                    UUID.randomUUID(), member.id(), e164, Timestamp.from(sentAt.plusSeconds(300)),
                    Timestamp.from(sentAt.plusSeconds(1)), Timestamp.from(sentAt));
        }

        ResponseEntity<ProblemView> response = sendCode(token, local, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo("OTP_DAILY_LIMIT");
        assertThat(response.getBody().detail())
                .isEqualTo("You've reached today's limit for this number. Try again tomorrow.");
        assertThat(outboxRowsFor(e164)).isZero();
    }

    @Test
    void aNumberVerifiedOnAnotherAccountIsRefusedAndNothingIsSent() {
        String owner = memberToken("phone-owner");
        String e164 = sendOk(owner);
        assertThat(verify(owner, latestCodeFor(e164), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        String other = memberToken("phone-other");
        ResponseEntity<ProblemView> response = sendCode(other, "0" + e164.substring(3), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).isEqualTo("This number is already verified on another account");
        assertThat(outboxRowsFor(e164)).isEqualTo(1);
    }

    @Test
    void aFourthDifferentNumberInADayIsRefusedAndNothingIsSentToIt() {
        String token = memberToken("phone-distinct");
        for (int i = 0; i < 3; i++) {
            sendOk(token);
        }
        String local = randomLocalNumber();

        ResponseEntity<ProblemView> fourth = sendCode(token, local, ProblemView.class);

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getBody().code()).isEqualTo("OTP_USER_NUMBER_LIMIT");
        assertThat(fourth.getBody().retryAfterSeconds()).isPositive();
        assertThat(fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(fourth.getBody().retryAfterSeconds().toString());
        assertThat(outboxRowsFor("+84" + local.substring(1))).isZero();
    }

    @Test
    void probingANumberVerifiedElsewhereSpendsTheProbersNumberQuotaAndStoresOnlyAHash() {
        String owner = memberToken("phone-probed-owner");
        String taken = sendOk(owner);
        assertThat(verify(owner, latestCodeFor(taken), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        UserView prober = newMember("phone-prober");
        String token = loginAs(prober);

        assertThat(sendCode(token, "0" + taken.substring(3), ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        sendOk(token);
        sendOk(token);
        ResponseEntity<ProblemView> third = sendCode(token, randomLocalNumber(), ProblemView.class);

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody().code()).isEqualTo("OTP_USER_NUMBER_LIMIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_send_attempt WHERE user_id = ? "
                        + "AND outcome = 'number_taken' AND phone_hash = ?",
                Long.class, prober.id(), phoneHasher.hash(taken))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_send_attempt WHERE phone_hash LIKE ?",
                Long.class, "%" + taken.substring(3) + "%")).isZero();
    }

    @Test
    void anotherMemberExhaustingTheirSendsToANumberDoesNotStopItsOwnerVerifyingIt() {
        String other = memberToken("phone-squatter");
        String owner = memberToken("phone-rightful");
        String local = randomLocalNumber();
        String e164 = "+84" + local.substring(1);
        Instant start = clock.instant();
        for (long at : new long[] {0, 60, 180, 480, 780}) {
            clock.set(start.plusSeconds(at));
            assertThat(sendCode(other, local, String.class).getStatusCode()).as("other's send at t=%d", at)
                    .isEqualTo(HttpStatus.OK);
        }
        clock.set(start.plusSeconds(781));
        assertThat(sendCode(other, local, ProblemView.class).getBody().code()).isEqualTo("OTP_HOURLY_LIMIT");

        ResponseEntity<SendCodeView> sent = sendCode(owner, local, SendCodeView.class);

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().resendAfterSeconds()).isEqualTo(60L);
        assertThat(verify(owner, latestCodeFor(e164), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/me/phone", owner, PhoneView.class).getBody())
                .isEqualTo(new PhoneView(e164, true, masked(e164)));
    }

    @Test
    void theRetentionJobDeletesOnlyAttemptsAndOutboxMessagesPastTheirWindows() {
        UserView member = newMember("phone-retention");
        Instant now = clock.instant();
        UUID oldAttempt = insertAttempt(member.id(), now.minus(Duration.ofDays(31)));
        UUID recentAttempt = insertAttempt(member.id(), now.minus(Duration.ofDays(29)));
        UUID oldMessage = insertOutbox(now.minus(Duration.ofDays(8)));
        UUID recentMessage = insertOutbox(now.minus(Duration.ofDays(6)));

        retentionJob.purge();

        assertThat(rowExists("phone_send_attempt", oldAttempt)).isFalse();
        assertThat(rowExists("phone_send_attempt", recentAttempt)).isTrue();
        assertThat(rowExists("sms_outbox", oldMessage)).isFalse();
        assertThat(rowExists("sms_outbox", recentMessage)).isTrue();
    }

    private UUID insertAttempt(UUID userId, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO phone_send_attempt (id, user_id, phone_hash, ip, outcome, created_at) "
                + "VALUES (?, ?, repeat('0', 64), '203.0.113.250', 'sent', ?)", id, userId, Timestamp.from(at));
        return id;
    }

    private UUID insertOutbox(Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sms_outbox (id, phone_e164, body, created_at) VALUES (?, '+84900000000', 'x', ?)",
                id, Timestamp.from(at));
        return id;
    }

    private boolean rowExists(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id) == 1;
    }

    @Test
    void verifyingASecondNumberReplacesTheFirst() {
        String token = memberToken("phone-replace");
        String first = sendOk(token);
        assertThat(verify(token, latestCodeFor(first), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        String second = sendOk(token);
        assertThat(verify(token, latestCodeFor(second), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/api/v1/me/phone", token, PhoneView.class).getBody())
                .isEqualTo(new PhoneView(second, true, masked(second)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_phone WHERE phone_e164 = ?", Long.class, first))
                .isZero();
    }

    @Test
    void twoConcurrentSendsToTheSameNumberLeaveExactlyOneCode() throws Exception {
        String token = memberToken("phone-race");
        String local = randomLocalNumber();
        String e164 = "+84" + local.substring(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpStatus> send = () -> {
                ready.countDown();
                go.await();
                return HttpStatus.valueOf(sendCode(token, local, String.class).getStatusCode().value());
            };
            Future<HttpStatus> a = pool.submit(send);
            Future<HttpStatus> b = pool.submit(send);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outboxRowsFor(e164)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phone_challenge WHERE phone_e164 = ?", Long.class, e164))
                .isEqualTo(1);
    }

    @Test
    void onlyAnAdminReadsTheOutboxNewestFirst() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("outbox-admin"), "password123", "Outbox Admin");
        UserView member = userService.createUser(admin.id(), uniqueEmail("outbox-member"), "password123",
                "Outbox Member", "MEMBER");
        String memberToken = loginAs(member);
        String older = sendOk(memberToken);
        clock.advance(Duration.ofSeconds(1));
        String newer = sendOk(memberToken);

        assertThat(get("/api/v1/admin/sms-outbox", memberToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<List<OutboxEntryView>> response = restTemplate.exchange("/api/v1/admin/sms-outbox?limit=50",
                HttpMethod.GET, bearer(loginAs(admin)), new ParameterizedTypeReference<List<OutboxEntryView>>() { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<OutboxEntryView> entries = response.getBody();
        assertThat(entries).hasSizeLessThanOrEqualTo(50);
        List<String> phones = entries.stream().map(OutboxEntryView::phone).toList();
        assertThat(phones).contains(newer, older);
        assertThat(phones.indexOf(newer)).isLessThan(phones.indexOf(older));
        OutboxEntryView newest = entries.get(phones.indexOf(newer));
        assertThat(newest.body()).matches(CODE_IN_BODY);
        assertThat(entries).extracting(OutboxEntryView::createdAt).isSortedAccordingTo((x, y) -> y.compareTo(x));
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.phone-verification.enabled=false")
    class WithTheSwitchOff {

        @Autowired
        private TestRestTemplate offRestTemplate;

        @Autowired
        private UserService offUserService;

        @Test
        void everyPhoneEndpointAnswers503() {
            UserView user = offUserService.createInitialAdmin(uniqueEmail("phone-off"), "password123", "Phone Off");
            ResponseEntity<TokenResponse> login = offRestTemplate.postForEntity("/api/v1/auth/login",
                    new LoginRequest(user.email(), "password123"), TokenResponse.class);
            HttpEntity<Object> auth = bearer(login.getBody().accessToken());

            List<ResponseEntity<ProblemView>> responses = List.of(
                    offRestTemplate.exchange("/api/v1/me/phone", HttpMethod.GET, auth, ProblemView.class),
                    offRestTemplate.exchange("/api/v1/me/phone/send-code", HttpMethod.POST,
                            bearer(login.getBody().accessToken(), new SendCodeBody("0912345678")), ProblemView.class),
                    offRestTemplate.exchange("/api/v1/me/phone/verify", HttpMethod.POST,
                            bearer(login.getBody().accessToken(), new VerifyBody("123456")), ProblemView.class));

            for (ResponseEntity<ProblemView> response : responses) {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
            }
        }
    }

    /**
     * The clock runs ten years ahead of every other case, so the window holds only this case's sends;
     * they are deleted again because the budget query has no upper bound.
     */
    @Nested
    @TestPropertySource(properties = "flowcortex.phone-verification.limits.global-sends-per-day=3")
    class WithASmallGlobalBudget {

        @Autowired
        private TestRestTemplate budgetRestTemplate;

        @Autowired
        private UserService budgetUserService;

        @Autowired
        private SettableClock budgetClock;

        @Autowired
        private JdbcTemplate budgetJdbc;

        @Test
        void theBudgetRefusesUntilTheSendThatFilledItLeavesTheWindow() {
            budgetRestTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
            Instant now = Instant.now().plus(Duration.ofDays(3650)).truncatedTo(ChronoUnit.SECONDS);
            budgetClock.set(now);
            UserView others = budgetUserService.createInitialAdmin(uniqueEmail("phone-budget-others"),
                    "password123", "Others");
            UserView asker = budgetUserService.createInitialAdmin(uniqueEmail("phone-budget"), "password123",
                    "Asker");
            try {
                insertAttempt(others, "sent", now.minus(Duration.ofHours(1)));
                insertAttempt(others, "sent", now.minus(Duration.ofHours(3)));
                insertAttempt(others, "cooldown", now.minus(Duration.ofHours(4)));
                insertAttempt(others, "sent", now.minus(Duration.ofHours(5)));
                insertAttempt(others, "sent", now.minus(Duration.ofHours(25)));
                ResponseEntity<TokenResponse> login = budgetRestTemplate.postForEntity("/api/v1/auth/login",
                        new LoginRequest(asker.email(), "password123"), TokenResponse.class);
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(login.getBody().accessToken());
                headers.set("X-Forwarded-For", "198.51.100.7");

                ResponseEntity<ProblemView> response = budgetRestTemplate.exchange("/api/v1/me/phone/send-code",
                        HttpMethod.POST, new HttpEntity<>(new SendCodeBody(randomLocalNumber()), headers),
                        ProblemView.class);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(response.getBody().code()).isEqualTo("OTP_GLOBAL_LIMIT");
                assertThat(response.getBody().retryAfterSeconds()).isEqualTo(Duration.ofHours(19).toSeconds());
            } finally {
                budgetJdbc.update("DELETE FROM phone_send_attempt WHERE user_id IN (?, ?)", others.id(), asker.id());
            }
        }

        private void insertAttempt(UserView user, String outcome, Instant at) {
            budgetJdbc.update("INSERT INTO phone_send_attempt (id, user_id, phone_hash, ip, outcome, created_at) "
                    + "VALUES (?, ?, ?, '198.51.100.8', ?, ?)", UUID.randomUUID(), user.id(), "b".repeat(64),
                    outcome, Timestamp.from(at));
        }
    }

    private String memberToken(String prefix) {
        return loginAs(newMember(prefix));
    }

    private UserView newMember(String prefix) {
        UserView admin = userService.createInitialAdmin(uniqueEmail(prefix + "-admin"), "password123", "Admin");
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Member", "MEMBER");
    }

    private String loginAs(UserView user) {
        String token = login(user.email(), "password123");
        assertThat(token).as("login of %s", user.email()).isNotNull();
        return token;
    }

    /** Sends a code to a fresh number and returns that number in E.164. */
    private String sendOk(String token) {
        String local = randomLocalNumber();
        assertThat(sendCode(token, local, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return "+84" + local.substring(1);
    }

    private <T> ResponseEntity<T> sendCode(String token, String phone, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-For", clientIp);
        return restTemplate.exchange("/api/v1/me/phone/send-code", HttpMethod.POST,
                new HttpEntity<>(new SendCodeBody(phone), headers), type);
    }

    private <T> ResponseEntity<T> verify(String token, String code, Class<T> type) {
        return post("/api/v1/me/phone/verify", token, new VerifyBody(code), type);
    }

    private String latestCodeFor(String e164) {
        String body = jdbc.queryForObject(
                "SELECT body FROM sms_outbox WHERE phone_e164 = ? ORDER BY created_at DESC LIMIT 1", String.class, e164);
        Matcher matcher = CODE_IN_BODY.matcher(body);
        assertThat(matcher.matches()).as("outbox body %s", body).isTrue();
        return matcher.group(1);
    }

    private long outboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM sms_outbox", Long.class);
    }

    private long outboxRowsFor(String e164) {
        return jdbc.queryForObject("SELECT count(*) FROM sms_outbox WHERE phone_e164 = ?", Long.class, e164);
    }

    /** {@code 09xxxxxxxx}: a valid Vietnamese mobile number no other case uses. */
    private static String randomLocalNumber() {
        return "09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String masked(String e164) {
        return "+84 " + e164.charAt(3) + "•• ••• " + e164.substring(e164.length() - 3);
    }

    private static HttpEntity<Object> bearer(String token) {
        return bearer(token, null);
    }

    private static HttpEntity<Object> bearer(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    /** UTC clock that stands still until a case moves it; never moved backwards. */
    static final class SettableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed to UTC");
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SettableClockConfig {

        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }
    }
}
