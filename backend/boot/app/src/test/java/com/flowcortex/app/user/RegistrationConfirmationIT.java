package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
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
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private CapturingMailSender mail;

    @Autowired
    private MovableClock clock;

    /** The JDK client returns a 429 as is; the default one would sleep through Retry-After. */
    private TestRestTemplate client;

    record RegisterRequest(String firstName, String lastName, String username, String email, String password) {
    }

    record ConfirmRequest(String token) {
    }

    record ResendRequest(String email) {
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
                .doesNotContain("refreshToken");
        assertThat(confirmed.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("refresh_token=") && cookie.contains("HttpOnly"));
        assertThat(login(expected, PASSWORD)).isNotBlank();
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
    void anEmailThatAlreadyHasAnAccountInAnotherCaseIsRefusedWith409() {
        String email = uniqueEmail("duplicate");
        assertThat(register(valid(email), String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

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
