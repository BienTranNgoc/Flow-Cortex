package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.emailconfirm.UnconfirmedAccountPurgeJob;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Self-registration with first/last name, optional username and email confirmation, against the
 * real app with the confirmation switch on. Outgoing mail is captured instead of sent, and the
 * clock can be moved forward to expire a link.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.registration.owner-email=" + RegistrationConfirmationIT.OWNER_EMAIL,
        "flowcortex.security.oauth.google.client-id=test-client.apps.googleusercontent.com",
        "flowcortex.security.oauth.google.client-secret=test-secret",
        "flowcortex.security.oauth.google.redirect-uri=http://localhost:5173/api/v1/auth/oauth/google/callback"})
@TestPropertySource(properties = "flowcortex.auth.email-confirmation.enabled=true")
@AutoConfigureTestRestTemplate
@Import(RegistrationConfirmationIT.MailAndClock.class)
class RegistrationConfirmationIT extends AbstractGoogleOAuthIT {

    static final String OWNER_EMAIL = "confirmation-owner@example.com";
    private static final String OWNER_PASSWORD = "owner-password-1";
    private static final String PASSWORD = "register-password-1";
    private static final String USERNAME_RULE = "Use 3–20 lowercase letters, digits or _";
    private static final Pattern LINK = Pattern.compile(
            "http://localhost:5173/confirm-email\\?token=([A-Za-z0-9_-]+)");
    private static final Pattern PASSWORD_LINK = Pattern.compile(
            "http://localhost:5173/set-password\\?token=([A-Za-z0-9_-]+)");
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private MovableClock clock;

    @Autowired
    private UnconfirmedAccountPurgeJob purgeJob;

    @Autowired
    private DataSource dataSource;

    /** The JDK client returns a 429 as is; the default one would sleep through Retry-After. */
    private TestRestTemplate client;

    record RegisterRequest(String firstName, String lastName, String username, String email, String password) {
    }

    record ConfirmRequest(String token) {
    }

    record ResendRequest(String email) {
    }

    record ConfirmedView(String accessToken, long expiresIn, boolean passwordSetupRequired,
            String passwordSetupToken) {
    }

    record PasswordRequest(String passwordSetupToken, String password, String firstName, String lastName,
            String username) {
    }

    record SessionView(String accessToken, long expiresIn) {
    }

    record MeView(String name, boolean hasPassword, boolean passwordSetupRequired) {
    }

    record StatusView(String status) {
    }

    record ProblemView(int status, String detail, String code, Map<String, String> errors) {
    }

    @BeforeEach
    void ownerAndClient() {
        client = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, OWNER_EMAIL) == 0) {
            userService.createInitialAdmin(OWNER_EMAIL, OWNER_PASSWORD, "Confirmation Owner");
        }
        clock.reset();
    }

    private static String nextIp() {
        return "203.0.113." + NEXT_CLIENT.getAndIncrement();
    }

    private <T> ResponseEntity<T> postFrom(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", nextIp());
        return client.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> register(RegisterRequest body, Class<T> type) {
        return postFrom("/api/v1/auth/register", body, type);
    }

    private static RegisterRequest valid(String email) {
        return new RegisterRequest("An", "Tran", null, email, PASSWORD);
    }

    private static RegisterRequest withUsername(String username) {
        return new RegisterRequest("An", "Tran", username, uniqueEmail("username"), PASSWORD);
    }

    private int accountsWith(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(email) = lower(?)", Integer.class,
                email);
    }

    /** Registers and returns the raw token from the one link mailed to the (lower-cased) address. */
    private String registerAndReadToken(String email) {
        assertThat(register(valid(email), StatusView.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mail.lastTokenFor(email.toLowerCase(Locale.ROOT));
    }

    @Test
    void aBlankLastNameIsRefusedAtItsFieldAndCreatesNoAccount() {
        String email = uniqueEmail("blank-last");

        ResponseEntity<ProblemView> response = register(
                new RegisterRequest("An", "   ", null, email, PASSWORD), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsKey("lastName").doesNotContainKey("firstName");
        assertThat(accountsWith(email)).isZero();
    }

    @Test
    void anEmailWithoutAnAtSignIsRefusedAtTheEmailField() {
        ResponseEntity<ProblemView> response = register(valid("an.example.com"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsKey("email");
    }

    @Test
    void usernamesOfThreeToTwentyLowercaseLettersDigitsOrUnderscoresAreAcceptedAndOthersRefused() {
        jdbc.update("UPDATE app_user SET username = NULL WHERE username IN ('an_1', 'abcdefghij0123456789')");

        assertThat(register(withUsername("an_1"), String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(register(withUsername("abcdefghij0123456789"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        for (String refused : List.of("ab", "abcdefghij0123456789x", "An_1", "an-1")) {
            ResponseEntity<ProblemView> response = register(withUsername(refused), ProblemView.class);
            assertThat(response.getStatusCode()).as(refused).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().errors()).as(refused).containsEntry("username", USERNAME_RULE);
        }
    }

    @Test
    void aTakenUsernameIsRefusedWith409AndCreatesNoAccount() {
        String username = "tk_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(register(withUsername(username), String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        RegisterRequest second = withUsername(username);

        ResponseEntity<ProblemView> response = register(second, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).isEqualTo("This username is taken");
        assertThat(response.getBody().errors()).containsEntry("username", "This username is taken");
        assertThat(accountsWith(second.email())).isZero();
    }

    @Test
    void aValidRegistrationMailsOneLinkToTheLowerCasedAddressIssuesNoSessionAndConfirmingSignsIn() {
        String lower = uniqueEmail("confirm-flow");
        String mixed = "An-" + lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1)
                .replace("@example.com", "@Example.COM");
        String expected = mixed.toLowerCase(Locale.ROOT);

        ResponseEntity<String> registered = register(valid(mixed), String.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(registered.getBody()).isEqualTo("{\"status\":\"confirmation_sent\"}");
        assertThat(registered.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(mail.sentTo(expected)).hasSize(1);
        CapturedMail message = mail.sentTo(expected).getFirst();
        assertThat(message.subject()).isEqualTo("Confirm your Flow-Cortex email");
        String token = tokenIn(message.body());
        assertThat(jdbc.queryForObject("SELECT email_confirmed_at IS NULL FROM app_user WHERE email = ?",
                Boolean.class, expected)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_confirmation_token t JOIN app_user u"
                + " ON u.id = t.user_id WHERE u.email = ? AND t.token_hash = ?", Integer.class, expected, token))
                .as("the raw token is never stored").isZero();

        ResponseEntity<ProblemView> beforeConfirm = postFrom("/api/v1/auth/login",
                new LoginRequest(expected, PASSWORD), ProblemView.class);
        assertThat(beforeConfirm.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(beforeConfirm.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(beforeConfirm.getBody().code()).isEqualTo("EMAIL_NOT_CONFIRMED");
        assertThat(beforeConfirm.getBody().detail()).isEqualTo("Confirm your email before signing in");

        ResponseEntity<String> confirmed = postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(token),
                String.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).contains("\"accessToken\"").contains("\"expiresIn\"")
                .contains("\"passwordSetupRequired\":true").doesNotContain("refreshToken");
        assertThat(confirmed.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("refresh_token=") && cookie.contains("HttpOnly"));
        assertThat(login(expected, PASSWORD)).as("the password chosen before the email was proven").isNull();
    }

    @Test
    void confirmingDropsThePasswordAndEndsEarlierSessionsAndTheMemberSetsANewOneOnce() {
        String email = uniqueEmail("set-password");
        String token = registerAndReadToken(email);
        UUID userId = userIdOf(email);
        UUID earlierSession = UUID.randomUUID();
        jdbc.update("INSERT INTO refresh_token (id, user_id, token_hash, family_id, issued_at, expires_at)"
                + " VALUES (?, ?, ?, ?, now(), now() + interval '1 day')", earlierSession, userId,
                "earlier-" + earlierSession, UUID.randomUUID());

        ResponseEntity<ConfirmedView> confirmed = postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(token),
                ConfirmedView.class);

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().passwordSetupRequired()).isTrue();
        String setupToken = confirmed.getBody().passwordSetupToken();
        assertThat(setupToken).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT has_password FROM app_user WHERE id = ?", Boolean.class, userId))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId))
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_token WHERE id = ?",
                Boolean.class, earlierSession)).isTrue();
        assertThat(login(email, PASSWORD)).isNull();

        String accessToken = confirmed.getBody().accessToken();
        MeView me = get("/api/v1/auth/me", accessToken, MeView.class).getBody();
        assertThat(me.hasPassword()).isFalse();
        assertThat(me.passwordSetupRequired()).isTrue();
        assertThat(me.name()).as("the name chosen at registration is dropped")
                .isEqualTo(email.substring(0, email.indexOf('@')));

        ResponseEntity<ProblemView> tooShort = put("/api/v1/me/password", accessToken,
                new PasswordRequest(setupToken, "short", "Binh", "Le", null), ProblemView.class);
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooShort.getBody().errors()).containsKey("password");
        ResponseEntity<ProblemView> bearerOnly = put("/api/v1/me/password", accessToken,
                new PasswordRequest("guessed-token", "new-password-1", "Binh", "Le", null), ProblemView.class);
        assertThat(bearerOnly.getStatusCode()).as("an access token alone sets no password")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bearerOnly.getBody().code()).isEqualTo("PASSWORD_SETUP_TOKEN_INVALID");
        assertThat(login(email, "new-password-1")).isNull();

        String username = "sp_" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<SessionView> set = put("/api/v1/me/password", accessToken,
                new PasswordRequest(setupToken, "new-password-1", "Binh", "Le", username), SessionView.class);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().accessToken()).isNotBlank();
        assertThat(set.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("refresh_token=") && cookie.contains("Path=/api/v1/auth"));
        assertThat(jdbc.queryForMap("SELECT name, username FROM app_user WHERE id = ?", userId))
                .containsEntry("name", "Binh Le").containsEntry("username", username);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId)).as("only the session the response started").isEqualTo(1);
        assertThat(mail.sentTo(email)).anyMatch(message -> message.subject()
                .equals("Your Flow-Cortex password was set"));
        assertThat(login(email, "new-password-1")).isNotBlank();

        ResponseEntity<ProblemView> again = put("/api/v1/me/password", set.getBody().accessToken(),
                new PasswordRequest(setupToken, "new-password-2", "A", "B", null), ProblemView.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(login(email, "new-password-1")).isNotBlank();
        assertThat(put("/api/v1/me/password", null,
                new PasswordRequest(setupToken, "new-password-3", "A", "B", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aConfirmedAccountWithoutAPasswordGetsBackInThroughASetPasswordLinkAndNothingElseDoes() {
        String email = uniqueEmail("password-link");
        String confirmLink = registerAndReadToken(email);
        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(confirmLink), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        String withPassword = uniqueEmail("password-link-has-one");
        userService.createInitialAdmin(withPassword, PASSWORD, "Has Password");
        String unknown = uniqueEmail("password-link-unknown");

        ResponseEntity<ProblemView> noPassword = postFrom("/api/v1/auth/login", new LoginRequest(email, "anything-1"),
                ProblemView.class);
        ResponseEntity<ProblemView> unknownLogin = postFrom("/api/v1/auth/login",
                new LoginRequest(unknown, "anything-1"), ProblemView.class);
        assertThat(noPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noPassword.getBody().detail()).isEqualTo(unknownLogin.getBody().detail());

        ResponseEntity<String> asked = postFrom("/api/v1/auth/resend-confirmation", new ResendRequest(email),
                String.class);
        ResponseEntity<String> askedWithPassword = postFrom("/api/v1/auth/resend-confirmation",
                new ResendRequest(withPassword), String.class);
        ResponseEntity<String> askedUnknown = postFrom("/api/v1/auth/resend-confirmation",
                new ResendRequest(unknown), String.class);
        assertThat(List.of(asked, askedWithPassword, askedUnknown)).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isEqualTo(asked.getBody());
        });
        assertThat(mail.sentTo(withPassword)).isEmpty();
        assertThat(mail.sentTo(unknown)).isEmpty();
        CapturedMail linkMail = mail.sentTo(email).getLast();
        assertThat(linkMail.subject()).isEqualTo("Set your Flow-Cortex password");
        Matcher matcher = PASSWORD_LINK.matcher(linkMail.body());
        assertThat(matcher.find()).isTrue();
        String link = matcher.group(1);

        assertPasswordLinkInvalid(postFrom("/api/v1/auth/password-link", new ConfirmRequest(confirmLink),
                ProblemView.class));
        assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(link), ProblemView.class));

        ResponseEntity<ConfirmedView> opened = postFrom("/api/v1/auth/password-link", new ConfirmRequest(link),
                ConfirmedView.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getBody().passwordSetupRequired()).isTrue();
        assertThat(put("/api/v1/me/password", opened.getBody().accessToken(),
                new PasswordRequest(opened.getBody().passwordSetupToken(), "new-password-1", "An", "Tran", null),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, "new-password-1")).isNotBlank();

        assertPasswordLinkInvalid(postFrom("/api/v1/auth/password-link", new ConfirmRequest(link),
                ProblemView.class));
        assertPasswordLinkInvalid(postFrom("/api/v1/auth/password-link", new ConfirmRequest("not-a-token"),
                ProblemView.class));
    }

    private static void assertPasswordLinkInvalid(ResponseEntity<ProblemView> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("PASSWORD_LINK_INVALID");
        assertThat(response.getBody().detail()).isEqualTo("This set-password link is no longer valid");
    }

    @Test
    void registeringAnUnconfirmedEmailAgainReplacesItsDetailsAndOnlyTheNewLinkWorks() {
        String email = uniqueEmail("re-register");
        String first = registerAndReadToken(email);
        UUID userId = userIdOf(email);
        String firstHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class,
                userId);
        String username = "rr_" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<String> again = register(
                new RegisterRequest("Binh", "Le", username, email.toUpperCase(Locale.ROOT), "second-password-1"),
                String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody()).isEqualTo("{\"status\":\"confirmation_sent\"}");
        assertThat(accountsWith(email)).isEqualTo(1);
        assertThat(userIdOf(email)).isEqualTo(userId);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT first_name, last_name, name, username, password_hash FROM app_user WHERE id = ?", userId);
        assertThat(row).containsEntry("first_name", "Binh").containsEntry("last_name", "Le")
                .containsEntry("name", "Binh Le").containsEntry("username", username);
        assertThat(row.get("password_hash")).isNotEqualTo(firstHash);
        assertThat(mail.sentTo(email)).hasSize(2);
        String second = mail.lastTokenFor(email);

        assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(first), ProblemView.class));
        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(second), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void googleSignInOnAnUnconfirmedEmailTakesTheAccountOverAndDropsItsPassword() {
        String email = uniqueEmail("google-takeover");
        String link = registerAndReadToken(email);
        UUID userId = userIdOf(email);
        String sub = "sub-" + UUID.randomUUID();
        googleKnows(sub, email);
        String ip = newClientIp();
        Flow flow = authorize("/", false, ip);

        ResponseEntity<String> response = callback(CODE, flow.state(), null, flow.cookie(), ip);

        assertThat(cookieValue(response, REFRESH_COOKIE)).as("the Google sign-in opens a session").isNotNull();
        Map<String, Object> row = jdbc.queryForMap("SELECT google_subject, has_password, password_hash, username,"
                + " name, email_confirmed_at IS NOT NULL AS confirmed FROM app_user WHERE id = ?", userId);
        assertThat(row).containsEntry("google_subject", sub).containsEntry("has_password", false)
                .containsEntry("password_hash", "").containsEntry("confirmed", true)
                .containsEntry("username", null)
                .containsEntry("name", email.substring(0, email.indexOf('@')));
        assertThat(accountsWith(email)).isEqualTo(1);
        assertThat(login(email, PASSWORD)).isNull();
        assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(link), ProblemView.class));
    }

    @Test
    void theSignupGrantWaitsForTheEmailConfirmation() throws InterruptedException {
        String email = uniqueEmail("grant-on-confirm");
        String token = registerAndReadToken(email);
        UUID userId = userIdOf(email);

        // Publications first: a grant handled in between leaves its ledger row behind for the next check.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Long.class, "%" + userId + "%")).as("no event names the unconfirmed account").isZero();
        assertThat(grantRows(userId)).isZero();

        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Instant deadline = Instant.now().plusSeconds(10);
        while (grantRows(userId) == 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(grantRows(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class, userId))
                .isEqualTo(1000);
    }

    @Test
    void unconfirmedAccountsPastTheRetentionWithNoLiveLinkAreDeletedWithTheirRowsAndOthersKept() {
        String abandoned = uniqueEmail("abandoned");
        registerAndReadToken(abandoned);
        UUID abandonedId = userIdOf(abandoned);
        jdbc.update("INSERT INTO refresh_token (id, user_id, token_hash, family_id, issued_at, expires_at)"
                + " VALUES (?, ?, ?, ?, now(), now() + interval '1 day')", UUID.randomUUID(), abandonedId,
                "abandoned-" + abandonedId, UUID.randomUUID());
        String resent = uniqueEmail("resent-late");
        registerAndReadToken(resent);
        String confirmedEmail = uniqueEmail("confirmed-kept");
        String token = registerAndReadToken(confirmedEmail);
        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        clock.advance(Duration.ofHours(73));
        try {
            assertThat(postFrom("/api/v1/auth/resend-confirmation", new ResendRequest(resent), StatusView.class)
                    .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            purgeJob.purge();
        } finally {
            clock.reset();
        }

        assertThat(accountsWith(abandoned)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_confirmation_token WHERE user_id = ?",
                Integer.class, abandonedId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id = ?", Integer.class,
                abandonedId)).isZero();
        assertThat(accountsWith(resent)).as("a link mailed late keeps the account").isEqualTo(1);
        assertThat(accountsWith(confirmedEmail)).isEqualTo(1);
    }

    @Test
    void anAccountAConcurrentRequestHoldsIsSkippedByThePurge() throws SQLException {
        String held = uniqueEmail("purge-held");
        registerAndReadToken(held);
        UUID heldId = userIdOf(held);
        jdbc.update("UPDATE email_confirmation_token SET used_at = now() WHERE user_id = ?", heldId);

        clock.advance(Duration.ofHours(73));
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM app_user WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, heldId);
                lock.executeQuery().close();
            }
            purgeJob.purge();
            assertThat(accountsWith(held)).as("a re-registration or resend holds the row").isEqualTo(1);
            other.rollback();
        } finally {
            clock.reset();
        }

        clock.advance(Duration.ofHours(73));
        try {
            purgeJob.purge();
        } finally {
            clock.reset();
        }
        assertThat(accountsWith(held)).as("released, it is purged on the next run").isZero();
    }

    private long grantRows(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE user_id = ? AND kind = 'grant'",
                Long.class, userId);
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE lower(email) = lower(?)", UUID.class, email);
    }

    @Test
    void aLinkUsedTwiceOrOpenedTwentyFiveHoursLaterIsNoLongerValid() {
        String used = registerAndReadToken(uniqueEmail("used-link"));
        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(used), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(used), ProblemView.class));

        String late = registerAndReadToken(uniqueEmail("expired-link"));
        clock.advance(Duration.ofHours(25));
        try {
            assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(late), ProblemView.class));
        } finally {
            clock.reset();
        }
        assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest("not-a-token"),
                ProblemView.class));
    }

    @Test
    void resendAlwaysAnswers202AndAnEarlierLinkStopsWorking() {
        String email = uniqueEmail("resend");
        String first = registerAndReadToken(email);

        ResponseEntity<StatusView> known = postFrom("/api/v1/auth/resend-confirmation",
                new ResendRequest(email.toUpperCase(Locale.ROOT)), StatusView.class);
        String unknownEmail = uniqueEmail("resend-unknown");
        ResponseEntity<StatusView> unknown = postFrom("/api/v1/auth/resend-confirmation",
                new ResendRequest(unknownEmail), StatusView.class);

        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getBody()).isEqualTo(known.getBody());
        assertThat(mail.sentTo(unknownEmail)).isEmpty();
        assertThat(mail.sentTo(email)).hasSize(2);
        String second = mail.lastTokenFor(email);
        assertThat(second).isNotEqualTo(first);

        assertLinkInvalid(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(first), ProblemView.class));
        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(second), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSuspendedAccountConfirmingItsEmailGetsNoGrantUntilAnAdminReactivatesIt() throws InterruptedException {
        String email = uniqueEmail("grant-suspended");
        String token = registerAndReadToken(email);
        UUID userId = userIdOf(email);
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", userId);

        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirmed(email)).isTrue();
        Thread.sleep(2000);
        assertThat(grantRows(userId)).as("no grant while suspended").isZero();

        String ownerToken = login(OWNER_EMAIL, OWNER_PASSWORD);
        assertThat(put("/api/v1/users/" + userId, ownerToken, new UpdateUserRequestView(null, null, null, "ACTIVE"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Instant deadline = Instant.now().plusSeconds(10);
        while (grantRows(userId) == 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(grantRows(userId)).isEqualTo(1);
    }

    @Test
    void aConfirmedEmailInAnotherCaseIsRefusedWith409() {
        String email = uniqueEmail("duplicate");
        String token = registerAndReadToken(email);
        assertThat(postFrom("/api/v1/auth/confirm-email", new ConfirmRequest(token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<ProblemView> second = register(valid(email.toUpperCase(Locale.ROOT)), ProblemView.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().detail()).isEqualTo("An account with this email already exists");
        assertThat(second.getBody().errors()).containsEntry("email", "An account with this email already exists");
        assertThat(accountsWith(email)).isEqualTo(1);
    }

    @Test
    void adminCreatedAndGoogleCreatedAccountsAreConfirmedAndSignInWithoutConfirming() {
        String adminCreated = uniqueEmail("admin-created");
        userService.createInitialAdmin(adminCreated, PASSWORD, "Admin Created");

        assertThat(login(adminCreated, PASSWORD)).isNotBlank();
        assertThat(confirmed(adminCreated)).isTrue();

        String googleEmail = uniqueEmail("google-created");
        googleKnows("sub-" + UUID.randomUUID(), googleEmail);
        String ip = newClientIp();
        Flow first = authorize("/", false, ip);
        ResponseEntity<String> signUp = callback(CODE, first.state(), null, first.cookie(), ip);
        assertThat(cookieValue(signUp, REFRESH_COOKIE)).as("Google sign-up opens a session").isNotNull();
        assertThat(confirmed(googleEmail)).isTrue();
        Flow again = authorize("/", false, ip);
        ResponseEntity<String> signIn = callback(CODE, again.state(), null, again.cookie(), ip);
        assertThat(cookieValue(signIn, REFRESH_COOKIE)).as("a later Google sign-in opens a session").isNotNull();
        assertThat(mail.sentTo(adminCreated)).isEmpty();
        assertThat(mail.sentTo(googleEmail)).isEmpty();
    }

    private Boolean confirmed(String email) {
        return jdbc.queryForObject("SELECT email_confirmed_at IS NOT NULL FROM app_user WHERE email = ?",
                Boolean.class, email);
    }

    private static void assertLinkInvalid(ResponseEntity<ProblemView> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("CONFIRMATION_LINK_INVALID");
        assertThat(response.getBody().detail()).isEqualTo("This confirmation link is no longer valid");
    }

    private static String tokenIn(String body) {
        Matcher matcher = LINK.matcher(body);
        assertThat(matcher.find()).as("a confirmation link in: %s", body).isTrue();
        return matcher.group(1);
    }

    /** Its own context: every bean it talks to is autowired here, not taken from the enclosing instance. */
    @Nested
    @TestPropertySource(properties = "flowcortex.auth.email-confirmation.enabled=false")
    class WithConfirmationOff {

        @Autowired
        private CapturingMailSender nestedMail;

        @Autowired
        private TestRestTemplate nestedRestTemplate;

        @Autowired
        private JdbcTemplate nestedJdbc;

        @Test
        void registerAnswers201WithASessionAndTheAccountIsConfirmed() {
            String email = uniqueEmail("confirmation-off");
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Forwarded-For", nextIp());

            ResponseEntity<String> response = nestedRestTemplate.exchange("/api/v1/auth/register", HttpMethod.POST,
                    new HttpEntity<>(valid(email), headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).contains("\"accessToken\"");
            assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                    .anyMatch(cookie -> cookie.startsWith("refresh_token="));
            assertThat(nestedJdbc.queryForObject("SELECT email_confirmed_at IS NOT NULL FROM app_user WHERE email = ?",
                    Boolean.class, email)).isTrue();
            assertThat(nestedMail.sentTo(email)).isEmpty();
            assertThat(nestedRestTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                    String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    record CapturedMail(List<String> to, String subject, String body) {
    }

    /** Records every message instead of opening an SMTP connection. */
    static class CapturingMailSender extends JavaMailSenderImpl {

        private final List<CapturedMail> sent = new CopyOnWriteArrayList<>();

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) throws MailException {
            for (MimeMessage message : mimeMessages) {
                try {
                    Address[] recipients = message.getAllRecipients();
                    sent.add(new CapturedMail(Arrays.stream(recipients).map(Address::toString).toList(),
                            message.getSubject(), String.valueOf(message.getContent())));
                } catch (MessagingException | IOException ex) {
                    throw new IllegalStateException("Could not read a captured message", ex);
                }
            }
        }

        List<CapturedMail> sentTo(String address) {
            return sent.stream().filter(message -> message.to().contains(address)).toList();
        }

        String lastTokenFor(String address) {
            List<CapturedMail> messages = sentTo(address);
            assertThat(messages).as("mail to %s", address).isNotEmpty();
            return tokenIn(messages.getLast().body());
        }
    }

    /** System time plus an offset a test can move; every reader of the app's Clock sees it. */
    static class MovableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
            offset.updateAndGet(current -> current.plus(by));
        }

        void reset() {
            offset.set(Duration.ZERO);
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
            return Instant.now().plus(offset.get());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailAndClock {

        @Bean
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
