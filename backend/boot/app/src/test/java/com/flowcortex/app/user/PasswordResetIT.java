package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.user.CapturingMailSender.CapturedMail;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Forgot password and reset over real HTTP with the switch on: who gets which mail, the link's
 * lifetime and single use, what a completed reset ends, and what the audit trail and the log hold.
 * Mail is captured instead of sent, and the clock can be moved forward to expire a link.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.registration.owner-email=" + PasswordResetIT.OWNER_EMAIL)
@TestPropertySource(properties = {
        "flowcortex.auth.password-reset.enabled=true",
        "flowcortex.auth.email-confirmation.enabled=true"})
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
@Import(PasswordResetIT.MailAndClock.class)
class PasswordResetIT extends AbstractUserModuleIT {

    static final String OWNER_EMAIL = "reset-owner@example.com";
    private static final String OWNER_PASSWORD = "owner-password-1";
    private static final String PASSWORD = "old-password-1";
    private static final String NEW_PASSWORD = "NewSecret8";
    private static final String RESET_REQUESTED = "{\"status\":\"reset_requested\"}";
    private static final Pattern RESET_LINK = Pattern.compile(
            "http://localhost:5173/reset-password#token=([A-Za-z0-9_-]+)");
    private static final Pattern CONFIRM_LINK = Pattern.compile(
            "http://localhost:5173/confirm-email\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern PASSWORD_LINK = Pattern.compile(
            "http://localhost:5173/set-password\\?token=([A-Za-z0-9_-]+)");
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private MovableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AppUserRepository users;

    /** The JDK client returns a 429 as is; the default one would sleep through Retry-After. */
    private TestRestTemplate client;

    private UUID ownerId;

    record ForgotRequest(String email) {
    }

    record ResetRequest(String token, String password) {
    }

    record ResetView(String status, String email) {
    }

    record RegisterRequest(String firstName, String lastName, String username, String email, String password) {
    }

    record TokenRequest(String token) {
    }

    record ConfirmedView(String accessToken, boolean passwordSetupRequired, String passwordSetupToken) {
    }

    record PasswordRequest(String passwordSetupToken, String password, String firstName, String lastName,
            String username) {
    }

    record ProblemView(int status, String detail, String code, Long retryAfterSeconds, Map<String, String> errors) {
    }

    @BeforeEach
    void ownerAndClient() {
        client = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, OWNER_EMAIL) == 0) {
            userService.createInitialAdmin(OWNER_EMAIL, OWNER_PASSWORD, "Reset Owner");
        }
        ownerId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, OWNER_EMAIL);
        clock.reset();
        mail.delayEachSend(Duration.ZERO);
        mail.refuseSends(false);
    }

    @Test
    void aKnownAndAnUnknownEmailGetTheSameAnswer() {
        String known = memberWithPassword("known");
        String unknown = uniqueEmail("nobody");

        ResponseEntity<String> forKnown = forgot(known);
        ResponseEntity<String> forUnknown = forgot(unknown);

        assertThat(List.of(forKnown, forUnknown)).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isEqualTo(RESET_REQUESTED);
        });
        assertThat(mail.sentTo(unknown)).isEmpty();
    }

    @Test
    void aSlowMailRelayDoesNotSlowTheAnswer() {
        List<String> known = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            known.add(memberWithPassword("timing"));
        }
        List<Long> knownMillis = new ArrayList<>();
        List<Long> unknownMillis = new ArrayList<>();
        mail.delayEachSend(Duration.ofMillis(500));
        try {
            for (String email : known) {
                knownMillis.add(timedForgot(email));
                unknownMillis.add(timedForgot(uniqueEmail("timing-unknown")));
            }
        } finally {
            mail.awaitQueuedSends();
            mail.delayEachSend(Duration.ZERO);
        }

        assertThat(Math.abs(median(knownMillis) - median(unknownMillis)))
                .as("known %s vs unknown %s", knownMillis, unknownMillis).isLessThan(50L);
        assertThat(known).allSatisfy(email -> assertThat(mail.sentTo(email)).hasSize(1));
    }

    @Test
    void aMalformedEmailIsRefusedAtTheEmailField() {
        ResponseEntity<ProblemView> response = postFrom("/api/v1/auth/forgot-password",
                new ForgotRequest("an.example.com"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsKey("email");
    }

    @Test
    void aConfirmedAccountWithAPasswordIsMailedOneResetLinkAtItsTrimmedLowerCasedAddress() {
        String email = memberWithPassword("link-mailed");
        String typed = " " + email.toUpperCase(Locale.ROOT) + " ";

        ResponseEntity<String> response = forgot(typed);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        List<CapturedMail> sent = mail.sentTo(email);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().subject()).isEqualTo("Reset your Flow-Cortex password");
        assertThat(sent.getFirst().body()).containsPattern(RESET_LINK).contains("The link works for 1 hour ");
    }

    @Test
    void aLinkOpenedSixtyOneMinutesLaterIsNoLongerValid() {
        String email = memberWithPassword("expired");
        String token = requestResetLink(email);

        clock.advance(Duration.ofMinutes(61));
        try {
            assertResetLinkInvalid(reset(token, NEW_PASSWORD, ProblemView.class));
        } finally {
            clock.reset();
        }
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNewerLinkRetiresTheOlderOne() {
        String email = memberWithPassword("two-requests");
        String first = requestResetLink(email);
        clock.advance(Duration.ofMinutes(5));
        String second;
        try {
            second = requestResetLink(email);
            assertResetLinkInvalid(reset(first, NEW_PASSWORD, ProblemView.class));
            assertThat(reset(second, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            clock.reset();
        }
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void aGoogleOnlyAccountIsToldItSignsInWithGoogleAndGetsNoResetLink() {
        String email = uniqueEmail("google-only");
        UUID userId = UUID.randomUUID();
        users.saveAndFlush(AppUser.googleSignedUp(userId, ownerId, email, "sub-" + UUID.randomUUID(), "Gia Le"));

        ResponseEntity<String> response = forgot(email);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(RESET_REQUESTED);
        List<CapturedMail> sent = mail.sentTo(email);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().subject()).isEqualTo("Your Flow-Cortex account signs in with Google");
        assertThat(sent.getFirst().body()).contains("http://localhost:5173/login").doesNotContain("/reset-password");
        assertThat(resetRows(userId)).isZero();
    }

    @Test
    void aSuspendedAccountGetsTheSameAnswerAndNoMail() {
        String email = memberWithPassword("suspended");
        setStatus(userIdOf(email), "SUSPENDED");

        ResponseEntity<String> response = forgot(email);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(RESET_REQUESTED);
        assertThat(mail.sentTo(email)).isEmpty();
        assertThat(resetRows(userIdOf(email))).isZero();
    }

    @Test
    void theFourthRequestForOneEmailWithinTheHourIsRateLimited() {
        String email = memberWithPassword("limited");

        for (int i = 0; i < 3; i++) {
            assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<ProblemView> fourth = postFrom("/api/v1/auth/forgot-password", new ForgotRequest(email),
                ProblemView.class);

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getBody().retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(fourth.getBody().retryAfterSeconds().toString());
        assertThat(mail.sentTo(email)).hasSize(3);
    }

    @Test
    void aNewPasswordIsAcceptedAndTheOldOneStopsWorking() {
        String email = memberWithPassword("accepted");
        String token = requestResetLink(email);

        ResponseEntity<ResetView> response = reset(token, NEW_PASSWORD, ResetView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new ResetView("password_reset", email));
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).as("no session is issued").isNull();
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTooShortPasswordIsRefusedAtItsFieldAndTheLinkKeepsWorking() {
        String email = memberWithPassword("too-short");
        String token = requestResetLink(email);

        ResponseEntity<ProblemView> tooShort = reset(token, "Short1", ProblemView.class);
        ResponseEntity<ProblemView> noToken = reset(" ", NEW_PASSWORD, ProblemView.class);

        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooShort.getBody().errors()).containsKey("password");
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noToken.getBody().errors()).containsKey("token");
        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aLinkUsedTwiceIsRefusedTheSecondTime() {
        String token = requestResetLink(memberWithPassword("used-twice"));
        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertResetLinkInvalid(reset(token, "AnotherSecret9", ProblemView.class));
        assertResetLinkInvalid(reset("not-a-token", NEW_PASSWORD, ProblemView.class));
    }

    @Test
    void theEleventhNewPasswordSubmittedFromOneIpWithinTheHourIsRateLimited(CapturedOutput output) {
        String ip = nextIp();
        for (int i = 0; i < 10; i++) {
            assertResetLinkInvalid(postFromIp(ip, "/api/v1/auth/reset-password",
                    new ResetRequest("guessed-token-" + i, NEW_PASSWORD), ProblemView.class));
        }

        ResponseEntity<ProblemView> eleventh = postFromIp(ip, "/api/v1/auth/reset-password",
                new ResetRequest("guessed-token-10", NEW_PASSWORD), ProblemView.class);

        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(eleventh.getBody().retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(eleventh.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(eleventh.getBody().retryAfterSeconds().toString());
        assertThat(output.getAll().lines().filter(line -> line.contains("password_reset outcome=submit_rate_limited")))
                .singleElement().asString().contains("limit=ip ").doesNotContain("guessed-token");
    }

    @Test
    void anAccountSuspendedAfterItsLinkWasSentKeepsItsPassword() {
        String email = memberWithPassword("suspended-later");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);
        String hashBefore = passwordHash(userId);
        setStatus(userId, "SUSPENDED");

        assertResetLinkInvalid(reset(token, NEW_PASSWORD, ProblemView.class));

        assertThat(passwordHash(userId)).isEqualTo(hashBefore);
    }

    @Test
    void aLinkSpentForAnAccountThatIsNoLongerActiveIsRefusedAndStaysUnspent(CapturedOutput output) {
        String email = memberWithPassword("inactive-at-spend");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);
        String hashBefore = passwordHash(userId);
        // Straight to the table: the admin path would retire the link, so the spend would never reach the
        // account-status check.
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", userId);

        assertResetLinkInvalid(reset(token, NEW_PASSWORD, ProblemView.class));

        assertThat(output.getAll()).contains("password_reset outcome=link_invalid reason=inactive userId=" + userId);
        assertThat(passwordHash(userId)).isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_confirmation_token WHERE user_id = ?"
                + " AND purpose = 'PASSWORD_RESET' AND used_at IS NULL", Integer.class, userId))
                .as("the rollback undid the spend").isEqualTo(1);
        assertThat(resetRows(userId)).isEqualTo(1);
    }

    @Test
    void aLinkSentBeforeASuspensionStaysDeadAfterTheAccountIsReactivated() {
        String email = memberWithPassword("suspended-then-active");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);
        String hashBefore = passwordHash(userId);
        setStatus(userId, "SUSPENDED");
        setStatus(userId, "ACTIVE");

        assertResetLinkInvalid(reset(token, NEW_PASSWORD, ProblemView.class));

        assertThat(passwordHash(userId)).isEqualTo(hashBefore);
    }

    @Test
    void twoTabsSpendingTheSameLinkAtOnceGetExactlyOneSuccess() throws Exception {
        String token = requestResetLink(memberWithPassword("two-tabs"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService tabs = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpStatusCode>> answers = new ArrayList<>();
            for (String password : List.of("FirstTab88", "SecondTab88")) {
                answers.add(tabs.submit(() -> {
                    start.await();
                    return reset(token, password, String.class).getStatusCode();
                }));
            }
            start.countDown();
            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> answer : answers) {
                statuses.add(answer.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_CONTENT);
        } finally {
            tabs.shutdownNow();
        }
    }

    @Test
    void aCompletedResetSignsOutTheOtherBrowser() {
        String email = memberWithPassword("other-browser");
        ResponseEntity<String> laptop = postFrom("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                String.class);
        String refreshCookie = laptop.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(cookie -> cookie.startsWith("refresh_token=")).findFirst().orElseThrow();
        String token = requestResetLink(email);

        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, refreshCookie.substring(0, refreshCookie.indexOf(';')));
        headers.set("X-Forwarded-For", nextIp());
        ResponseEntity<String> refresh = client.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aLockedAccountSignsInWithItsNewPasswordRightAfterTheReset() {
        String email = memberWithPassword("locked");
        for (int i = 0; i < 5; i++) {
            assertThat(loginStatus(email, "wrong-password-" + i)).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(HttpStatus.LOCKED);
        String token = requestResetLink(email);

        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anOpenEmailedLinkOfAnotherKindStopsWorkingAfterTheReset() {
        String email = memberWithPassword("other-kind");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);
        String confirmation = OpaqueTokens.generateRawToken();
        jdbc.update("INSERT INTO email_confirmation_token (id, user_id, purpose, token_hash, expires_at, created_at)"
                + " VALUES (?, ?, 'EMAIL_CONFIRM', ?, now() + interval '1 day', now())", UUID.randomUUID(), userId,
                OpaqueTokens.hash(confirmation));

        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ProblemView> opened = postFrom("/api/v1/auth/confirm-email", new TokenRequest(confirmation),
                ProblemView.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(opened.getBody().code()).isEqualTo("CONFIRMATION_LINK_INVALID");
    }

    @Test
    void aCompletedResetMailsAPasswordChangedNoticeAndAnswersWithTheEmail() {
        String email = memberWithPassword("notice");
        String token = requestResetLink(email);

        ResponseEntity<ResetView> response = reset(token, NEW_PASSWORD, ResetView.class);

        assertThat(response.getBody().email()).isEqualTo(email);
        List<CapturedMail> notices = mail.sentTo(email).stream()
                .filter(message -> message.subject().equals("Your Flow-Cortex password was changed")).toList();
        assertThat(notices).hasSize(1);
        assertThat(notices.getFirst().body()).contains("We signed you out on every device. A device that was"
                + " already open may stay signed in for up to 15 minutes.");
    }

    @Test
    void aRelayThatRefusesTheNoticeDoesNotFailTheReset() {
        String email = memberWithPassword("relay-down");
        String token = requestResetLink(email);
        mail.refuseSends(true);
        ResponseEntity<ResetView> response;
        try {
            response = reset(token, NEW_PASSWORD, ResetView.class);
            mail.awaitQueuedSends();
        } finally {
            mail.refuseSends(false);
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyAHashOfTheTokenIsStoredAndTheLogHoldsNeitherTokenNorLink(CapturedOutput output) {
        String email = memberWithPassword("at-rest");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);

        String stored = jdbc.queryForObject("SELECT token_hash FROM email_confirmation_token"
                + " WHERE user_id = ? AND purpose = 'PASSWORD_RESET'", String.class, userId);
        assertThat(stored).matches("[0-9a-f]{64}").isEqualTo(OpaqueTokens.hash(token));
        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        mail.awaitQueuedSends();

        assertThat(output.getAll()).doesNotContain(token).doesNotContain("#token=");
    }

    @Test
    void aRequestAndACompletionEachLeaveOneAuditRowForTheUser() {
        String email = memberWithPassword("audit");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);
        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(auditRows("PASSWORD_RESET_REQUESTED", userId)).isEqualTo(1);
        assertThat(auditRows("PASSWORD_RESET_COMPLETED", userId)).isEqualTo(1);
    }

    @Test
    void everyRequestOutcomeLogsOneLineWithTheUserIdAndNoEmail(CapturedOutput output) {
        String unknown = uniqueEmail("lines-unknown");
        String unconfirmed = uniqueEmail("lines-unconfirmed");
        register(unconfirmed);
        String noPassword = uniqueEmail("lines-no-password");
        confirm(register(noPassword));
        String google = uniqueEmail("lines-google");
        UUID googleId = UUID.randomUUID();
        users.saveAndFlush(AppUser.googleSignedUp(googleId, ownerId, google, "sub-" + UUID.randomUUID(), "Gia Le"));
        String withPassword = memberWithPassword("lines-password");
        String failing = memberWithPassword("lines-failing");
        String ip = nextIp();

        for (String email : List.of(unknown, unconfirmed, noPassword, google, withPassword)) {
            assertThat(postFromIp(ip, "/api/v1/auth/forgot-password", new ForgotRequest(email), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(postFromIp(ip, "/api/v1/auth/forgot-password", new ForgotRequest(withPassword), String.class)
                .getStatusCode()).as("the sixth request from one IP within the hour")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String token = mail.lastTokenFor(withPassword, RESET_LINK);
        assertThat(reset(token, NEW_PASSWORD, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        mail.refuseSends(true);
        try {
            assertThat(forgot(failing).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            mail.awaitQueuedSends();
        } finally {
            mail.refuseSends(false);
        }

        List<String> lines = output.getAll().lines()
                .filter(line -> line.contains("password_reset outcome=") || line.contains("email_confirmation outcome=")
                        || line.contains("mail.send "))
                .toList();
        assertThat(linesContaining(lines, "password_reset outcome=skipped reason=unknown_email")).isEqualTo(1);
        assertThat(linesContaining(lines, "password_reset outcome=confirmation_link userId=" + userIdOf(unconfirmed)))
                .isEqualTo(1);
        assertThat(linesContaining(lines, "password_reset outcome=password_link userId=" + userIdOf(noPassword)))
                .isEqualTo(1);
        assertThat(linesContaining(lines, "password_reset outcome=google_notice userId=" + googleId)).isEqualTo(1);
        assertThat(linesContaining(lines, "password_reset outcome=sent userId=" + userIdOf(withPassword)))
                .isEqualTo(1);
        assertThat(linesContaining(lines, "password_reset outcome=sent userId=" + userIdOf(failing))).isEqualTo(1);
        assertThat(lines).filteredOn(line -> line.contains("password_reset outcome=rate_limited")).singleElement()
                .asString().contains("limit=ip ");
        assertThat(linesContaining(lines, "password_reset outcome=completed userId=" + userIdOf(withPassword)))
                .isEqualTo(1);
        assertThat(lines).filteredOn(line -> line.contains("mail.send key=reset-password outcome=failed memberId="
                + userIdOf(failing))).hasSize(1);
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .doesNotContain(unknown, unconfirmed, noPassword, google, withPassword, failing));
    }

    @Test
    void anUnknownEmailIsNeitherAuditedNorLoggedByItsAddress(CapturedOutput output) {
        String unknown = uniqueEmail("nobody");

        assertThat(forgot(unknown).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE metadata::text LIKE ?",
                Integer.class, "%" + unknown + "%")).isZero();
        assertThat(output.getAll()).doesNotContain(unknown);
    }

    @Test
    void anUnconfirmedAccountIsMailedANewConfirmationLinkAndThenChoosesItsPassword() {
        String email = uniqueEmail("unconfirmed");
        String earlier = register(email);

        assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        List<CapturedMail> sent = mail.sentTo(email);
        assertThat(sent).hasSize(2);
        CapturedMail reissued = sent.getLast();
        assertThat(reissued.body()).startsWith("You asked to reset your Flow-Cortex password.")
                .containsPattern(CONFIRM_LINK).doesNotContainIgnoringCase("ignore").doesNotContain("/reset-password");
        ResponseEntity<ProblemView> old = postFrom("/api/v1/auth/confirm-email", new TokenRequest(earlier),
                ProblemView.class);
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(old.getBody().code()).isEqualTo("CONFIRMATION_LINK_INVALID");
        assertThat(resetRows(userIdOf(email))).isZero();

        ConfirmedView confirmed = confirm(mail.lastTokenFor(email, CONFIRM_LINK));
        assertThat(loginStatus(email, PASSWORD)).as("the password chosen at registration").isEqualTo(
                HttpStatus.UNAUTHORIZED);
        choosePassword(confirmed);
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aConfirmedAccountWithoutAPasswordIsMailedASetPasswordLinkAndThenChoosesOne() {
        String email = uniqueEmail("no-password");
        confirm(register(email));
        UUID userId = userIdOf(email);

        assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        List<CapturedMail> sent = mail.sentTo(email);
        assertThat(sent).filteredOn(message -> PASSWORD_LINK.matcher(message.body()).find()).hasSize(1);
        assertThat(sent).noneMatch(message -> message.body().contains("/reset-password"));
        assertThat(resetRows(userId)).isZero();
        ResponseEntity<ConfirmedView> opened = postFrom("/api/v1/auth/password-link",
                new TokenRequest(mail.lastTokenFor(email, PASSWORD_LINK)), ConfirmedView.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        choosePassword(opened.getBody());
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aLinkMailedBeforeAnAdminChangedTheEmailNoLongerResetsThePassword() {
        String email = memberWithPassword("email-changed");
        UUID userId = userIdOf(email);
        String token = requestResetLink(email);
        String hashBefore = passwordHash(userId);
        String newEmail = uniqueEmail("email-changed-new");

        assertThat(put("/api/v1/users/" + userId, ownerToken(),
                new UpdateUserRequestView(newEmail, null, null, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertResetLinkInvalid(reset(token, NEW_PASSWORD, ProblemView.class));
        assertThat(passwordHash(userId)).isEqualTo(hashBefore);

        int mailedToOldAddress = mail.sentTo(email).size();
        ResponseEntity<String> forOldAddress = forgot(email);
        assertThat(forOldAddress.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(forOldAddress.getBody()).isEqualTo(RESET_REQUESTED);
        assertThat(mail.sentTo(email)).as("nothing new for the old address").hasSize(mailedToOldAddress);

        String fresh = requestResetLink(newEmail);
        ResponseEntity<ResetView> completed = reset(fresh, NEW_PASSWORD, ResetView.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).isEqualTo(new ResetView("password_reset", newEmail));
        assertThat(loginStatus(newEmail, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    private static String nextIp() {
        return "192.0.2." + NEXT_CLIENT.getAndIncrement();
    }

    private <T> ResponseEntity<T> postFrom(String path, Object body, Class<T> type) {
        return postFromIp(nextIp(), path, body, type);
    }

    private <T> ResponseEntity<T> postFromIp(String ip, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        return client.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<String> forgot(String email) {
        return postFrom("/api/v1/auth/forgot-password", new ForgotRequest(email), String.class);
    }

    private long timedForgot(String email) {
        long started = System.nanoTime();
        assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private <T> ResponseEntity<T> reset(String token, String password, Class<T> type) {
        return postFrom("/api/v1/auth/reset-password", new ResetRequest(token, password), type);
    }

    private String requestResetLink(String email) {
        assertThat(forgot(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mail.lastTokenFor(email, RESET_LINK);
    }

    private static void assertResetLinkInvalid(ResponseEntity<ProblemView> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("RESET_LINK_INVALID");
        assertThat(response.getBody().detail()).isEqualTo("This reset link is no longer valid");
    }

    /** A confirmed ACTIVE member of the registration owner, signing in with {@link #PASSWORD}. */
    private String memberWithPassword(String prefix) {
        String email = uniqueEmail(prefix);
        userService.createUser(ownerId, email, PASSWORD, "An Tran", "MEMBER");
        return email;
    }

    /** Registers with confirmation on and returns the token of the confirmation link mailed for it. */
    private String register(String email) {
        assertThat(postFrom("/api/v1/auth/register", new RegisterRequest("An", "Tran", null, email, PASSWORD),
                String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mail.lastTokenFor(email, CONFIRM_LINK);
    }

    private ConfirmedView confirm(String token) {
        ResponseEntity<ConfirmedView> confirmed = postFrom("/api/v1/auth/confirm-email", new TokenRequest(token),
                ConfirmedView.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return confirmed.getBody();
    }

    private void choosePassword(ConfirmedView signedIn) {
        assertThat(signedIn.passwordSetupRequired()).isTrue();
        assertThat(put("/api/v1/me/password", signedIn.accessToken(),
                new PasswordRequest(signedIn.passwordSetupToken(), NEW_PASSWORD, "An", "Tran", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpStatusCode loginStatus(String email, String password) {
        return postFrom("/api/v1/auth/login", new LoginRequest(email, password), String.class).getStatusCode();
    }

    private String ownerToken() {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        assertThat(token).isNotBlank();
        return token;
    }

    private void setStatus(UUID userId, String status) {
        assertThat(put("/api/v1/users/" + userId, ownerToken(), new UpdateUserRequestView(null, null, null, status),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE lower(email) = lower(?)", UUID.class, email);
    }

    private String passwordHash(UUID userId) {
        return jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
    }

    private int resetRows(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM email_confirmation_token WHERE user_id = ?"
                + " AND purpose = 'PASSWORD_RESET'", Integer.class, userId);
    }

    private static long linesContaining(List<String> lines, String text) {
        return lines.stream().filter(line -> line.contains(text)).count();
    }

    private int auditRows(String type, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE type = ? AND user_id = ?", Integer.class,
                type, userId);
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
