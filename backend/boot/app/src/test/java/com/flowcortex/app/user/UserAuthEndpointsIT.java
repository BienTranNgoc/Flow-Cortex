package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.api.UserView;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code /api/v1/auth} against the real, fully-wired app. Covers login + me,
 * suspended login (403), self profile update, and self-registration with its
 * owner and rate limits.
 *
 * <p>Every register request names its client through {@code X-Forwarded-For}, which the server
 * honours from a loopback proxy, so each test fills its own per-IP bucket rather than 127.0.0.1's.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.registration.owner-email=" + UserAuthEndpointsIT.OWNER_EMAIL)
@AutoConfigureTestRestTemplate
@Import(UserAuthEndpointsIT.UserActivatedEvents.class)
class UserAuthEndpointsIT extends AbstractUserModuleIT {

    static final String OWNER_EMAIL = "registration-owner@example.com";
    private static final String OWNER_PASSWORD = "owner-password-1";
    /** The application.yml defaults: 5 per IP and 3 per email, each per hour. */
    private static final int PER_IP_CAPACITY = 5;
    private static final int PER_EMAIL_CAPACITY = 3;
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    /**
     * The default Apache client retries a 429 after sleeping its whole Retry-After (up to an hour
     * here); the JDK client returns the 429 as is.
     */
    private TestRestTemplate registerClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserActivatedEvents activatedEvents;

    record ProblemView(int status, String detail) {
    }

    /** The configured owner: an active self-owned ADMIN, created once per database. */
    private String ownerToken() {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        if (token != null) {
            return token;
        }
        userService.createInitialAdmin(OWNER_EMAIL, OWNER_PASSWORD, "Registration Owner");
        return login(OWNER_EMAIL, OWNER_PASSWORD);
    }

    private static String newClientIp() {
        return "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    private <T> ResponseEntity<T> register(String clientIp, RegisterRequest body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        return registerClient.exchange("/api/v1/auth/register", HttpMethod.POST, new HttpEntity<>(body, headers),
                responseType);
    }

    @BeforeEach
    void ensureOwnerExists() {
        registerClient = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
        assertThat(ownerToken()).isNotBlank();
    }

    @Test
    void loginSuccessReturnsTokenAndMeReflectsItWithoutPassword() {
        String email = uniqueEmail("admin-login");
        userService.createInitialAdmin(email, "correct-password-1", "Login Admin");

        ResponseEntity<TokenResponse> loginResponse =
                restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, "correct-password-1"),
                        TokenResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = loginResponse.getBody().accessToken();
        assertThat(token).isNotBlank();

        ResponseEntity<MeView> meResponse = get("/api/v1/auth/me", token, MeView.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        MeView me = meResponse.getBody();
        assertThat(me.email()).isEqualTo(email);
        assertThat(me.role()).isEqualTo("ADMIN");
        // The body says whether a password exists, never the password or its hash.
        ResponseEntity<String> rawMe = get("/api/v1/auth/me", token, String.class);
        assertThat(rawMe.getBody()).doesNotContain("\"password\"").doesNotContain("passwordHash")
                .doesNotContain("$2a$").contains("\"hasPassword\":true")
                .contains("\"passwordSetupRequired\":false");
    }

    @Test
    void loginWrongPasswordReturns401ProblemDetail() {
        String email = uniqueEmail("admin-badpass");
        userService.createInitialAdmin(email, "correct-password-1", "Bad Pass Admin");

        ResponseEntity<String> response = loginRaw(email, "not-the-right-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void loginUnknownEmailReturns401NotDisclosingWhichPartWasWrong() {
        ResponseEntity<String> response = loginRaw(uniqueEmail("never-registered"), "whatever-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginSuspendedUserCorrectPasswordReturns403AndNoTokenIsUsable() {
        // A self-owned ADMIN (createInitialAdmin) can no longer suspend
        // itself (the self-lockout guard, UserServiceImpl#updateUser), so
        // this suspends a MEMBER managed by an owning admin instead — "a
        // suspended user cannot log in" doesn't require the suspended user
        // to be a self-owned admin.
        UserView owningAdmin =
                userService.createInitialAdmin(uniqueEmail("admin-for-suspended-login"), "correct-password-1",
                        "Owning Admin");
        String email = uniqueEmail("member-suspended-login");
        UserView member = userService.createUser(owningAdmin.id(), email, "correct-password-1", "Suspended Member",
                "MEMBER");
        userService.updateUser(owningAdmin.id(), member.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response = loginRaw(email, "correct-password-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void suspendedUserWithStillValidTokenReturns403OnMe() {
        String email = uniqueEmail("member-live-suspend-me");
        UserView admin = userService.createInitialAdmin(uniqueEmail("admin-live-suspend-me"), "correct-password-1",
                "Live Suspend Admin");
        UserView member = userService.createUser(admin.id(), email, "correct-password-1", "Soon Suspended",
                "MEMBER");
        String token = login(email, "correct-password-1");
        assertThat(token).isNotBlank();

        userService.updateUser(admin.id(), member.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response = get("/api/v1/auth/me", token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void suspendedUserWithStillValidTokenReturns403OnProfileUpdate() {
        String email = uniqueEmail("member-live-suspend-profile");
        UserView admin = userService.createInitialAdmin(uniqueEmail("admin-live-suspend-profile"),
                "correct-password-1", "Live Suspend Admin");
        UserView member = userService.createUser(admin.id(), email, "correct-password-1", "Soon Suspended",
                "MEMBER");
        String token = login(email, "correct-password-1");
        assertThat(token).isNotBlank();

        userService.updateUser(admin.id(), member.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response =
                put("/api/v1/auth/profile", token, new ProfileUpdateRequest("New Name"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void unauthenticatedMeReturns401ProblemDetail() {
        ResponseEntity<String> response = get("/api/v1/auth/me", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void updateProfileChangesOwnNameOnlyMeReflectsIt() {
        String email = uniqueEmail("member-profile");
        UserView admin = userService.createInitialAdmin(uniqueEmail("admin-for-member"), "correct-password-1",
                "Owning Admin");
        userService.createUser(admin.ownerId(), email, "correct-password-1", "Original Name", "MEMBER");
        String token = login(email, "correct-password-1");

        ResponseEntity<MeView> updateResponse =
                put("/api/v1/auth/profile", token, new ProfileUpdateRequest("New Display Name"), MeView.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().name()).isEqualTo("New Display Name");
        // email is unchanged by a profile update (name-only).
        assertThat(updateResponse.getBody().email()).isEqualTo(email);

        ResponseEntity<MeView> meAfter = get("/api/v1/auth/me", token, MeView.class);
        assertThat(meAfter.getBody().name()).isEqualTo("New Display Name");
    }

    @Test
    void registerNewEmailReturns201WithTokenAndTheAccountCanLogIn() {
        String email = uniqueEmail("self-registered");

        ResponseEntity<TokenResponse> response = register(newClientIp(),
                new RegisterRequest(email, "register-password-1", "Self Registered"), TokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = response.getBody().accessToken();
        assertThat(token).isNotBlank();
        MeView me = get("/api/v1/auth/me", token, MeView.class).getBody();
        assertThat(me.email()).isEqualTo(email);
        assertThat(me.role()).isEqualTo("MEMBER");

        assertThat(login(email, "register-password-1")).isNotBlank();
    }

    @Test
    void anEmailRegisteredInMixedCaseLogsInWithTheLowerCaseSpellingAndStaysUniqueAcrossCase() {
        String lower = uniqueEmail("case-sensitive");

        ResponseEntity<String> first = register(newClientIp(),
                new RegisterRequest(lower.toUpperCase(Locale.ROOT), "register-password-1", "Case"), String.class);
        ResponseEntity<String> second = register(newClientIp(),
                new RegisterRequest(lower, "register-password-1", "Case Twin"), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(login(lower, "register-password-1")).isNotBlank();
        assertThat(login(lower.toUpperCase(Locale.ROOT), "register-password-1")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE lower(email) = ?", String.class, lower))
                .isEqualTo(lower);
    }

    @Test
    void anOverlongEmailIsRejectedAs400OnLoginAndRegister() {
        String overlong = "a".repeat(250) + "@x.io";

        assertThat(loginRaw(overlong, "whatever-password").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(register(newClientIp(), new RegisterRequest(overlong, "register-password-1", "Long"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerAnswersTheAccessTokenOnlySetsTheRefreshCookieAndIsAuditedOnce() {
        String email = uniqueEmail("register-shape");
        String clientIp = newClientIp();

        ResponseEntity<String> response = register(clientIp,
                new RegisterRequest(email, "register-password-1", "Shape"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"accessToken\"").contains("\"expiresIn\"")
                .doesNotContain("refreshToken");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).anyMatch(
                cookie -> cookie.startsWith("refresh_token=") && cookie.contains("HttpOnly")
                        && !cookie.contains("Max-Age"));
        assertThat(auditCount("REGISTRATION_ACCEPTED", email)).isEqualTo(1);
        assertThat(auditCount("LOGIN_SUCCESS", email)).isZero();
    }

    @Test
    void aRateLimitedRegistrationIsAudited() {
        String clientIp = newClientIp();
        for (int attempt = 1; attempt <= PER_IP_CAPACITY; attempt++) {
            register(clientIp, new RegisterRequest(uniqueEmail("register-audit"), "register-password-1", "A"),
                    String.class);
        }

        register(clientIp, new RegisterRequest(uniqueEmail("register-audit"), "register-password-1", "Over"),
                String.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE type = ? AND ip = ?", Integer.class,
                "REGISTRATION_REJECTED_RATE_LIMITED", clientIp)).isEqualTo(1);
    }

    private int auditCount(String type, String email) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event e JOIN app_user u ON u.id = e.user_id"
                + " WHERE e.type = ? AND u.email = ?", Integer.class, type, email);
    }

    @Test
    void aRegisteredAccountIsListedByTheConfiguredOwnerWhoCanSuspendIt() {
        String email = uniqueEmail("register-owned");
        register(newClientIp(), new RegisterRequest(email, "register-password-1", "Owned"), TokenResponse.class);
        String ownerToken = ownerToken();

        ResponseEntity<UserResponseView[]> list = get("/api/v1/users", ownerToken, UserResponseView[].class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserResponseView registered = Arrays.stream(list.getBody())
                .filter(user -> user.email().equals(email))
                .findFirst()
                .orElseThrow(() -> new AssertionError("registered user not listed by the owner"));
        assertThat(registered.role()).isEqualTo("MEMBER");
        assertThat(registered.status()).isEqualTo("ACTIVE");

        ResponseEntity<String> suspend = put("/api/v1/users/" + registered.id(), ownerToken,
                new UpdateUserRequestView(null, null, null, "SUSPENDED"), String.class);

        assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginRaw(email, "register-password-1").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void registerOverThePerIpLimitReturns429WithRetryAfterWhileAnotherClientStillRegisters() {
        String clientIp = newClientIp();
        for (int attempt = 1; attempt <= PER_IP_CAPACITY; attempt++) {
            ResponseEntity<String> accepted = register(clientIp,
                    new RegisterRequest(uniqueEmail("register-ip"), "register-password-1", "Ip " + attempt),
                    String.class);
            assertThat(accepted.getStatusCode()).as("attempt %d", attempt).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<ProblemView> limited = register(clientIp,
                new RegisterRequest(uniqueEmail("register-ip"), "register-password-1", "Ip over"), ProblemView.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(limited.getBody().detail()).isEqualTo("Too many registration attempts; try again later");
        assertThat(Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3600L);

        ResponseEntity<String> otherClient = register(newClientIp(),
                new RegisterRequest(uniqueEmail("register-ip-other"), "register-password-1", "Other"),
                String.class);
        assertThat(otherClient.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void registerOverThePerEmailLimitReturns429EvenFromFreshClientsAndAnyLetterCase() {
        String email = uniqueEmail("register-email-limit");
        for (int attempt = 1; attempt <= PER_EMAIL_CAPACITY; attempt++) {
            ResponseEntity<String> passed = register(newClientIp(),
                    new RegisterRequest(email, "register-password-1", "Email " + attempt), String.class);
            assertThat(passed.getStatusCode()).as("attempt %d", attempt)
                    .isEqualTo(attempt == 1 ? HttpStatus.CREATED : HttpStatus.CONFLICT);
        }

        ResponseEntity<String> limited = register(newClientIp(),
                new RegisterRequest(email.toUpperCase(Locale.ROOT), "register-password-1", "Email over"),
                String.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void registeringWithConfirmationOffAndAdminCreatingAnAccountEachPublishOneActivationInTheirTransaction() {
        ResponseEntity<TokenResponse> response = register(newClientIp(),
                new RegisterRequest(uniqueEmail("register-event"), "register-password-1", "Event"),
                TokenResponse.class);
        UUID registered = get("/api/v1/auth/me", response.getBody().accessToken(), MeView.class).getBody().id();
        UUID adminCreated = userService.createInitialAdmin(uniqueEmail("admin-event"), "admin-password-1",
                "Event Admin").id();

        assertThat(activatedEvents.received()).filteredOn(event -> event.userId().equals(registered)).hasSize(1);
        assertThat(activatedEvents.received()).filteredOn(event -> event.userId().equals(adminCreated)).hasSize(1);
    }

    @Test
    void registerDuplicateEmailReturns409() {
        String email = uniqueEmail("register-duplicate");
        String clientIp = newClientIp();
        register(clientIp, new RegisterRequest(email, "register-password-1", "First"), String.class);

        ResponseEntity<String> second = register(clientIp,
                new RegisterRequest(email, "register-password-2", "Second"), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void registerPasswordOver72Utf8BytesReturns400NamingTheField() {
        // 25 characters but 73 UTF-8 bytes: within a character limit of 72, past BCrypt's byte limit.
        String password = "ế".repeat(24) + "a";

        ResponseEntity<String> response = register(newClientIp(),
                new RegisterRequest(uniqueEmail("register-long-password"), password, "Long Password"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).contains("\"password\"");
    }

    record ProfileUpdateRequest(String name) {
    }

    record RegisterRequest(String email, String password, String firstName, String lastName) {
        RegisterRequest(String email, String password, String firstName) {
            this(email, password, firstName, "Member");
        }
    }

    /**
     * An after-commit listener only fires for an event published inside a transaction that then
     * committed, so a recorded event proves both. Registration answers on a server thread, which
     * {@code @RecordApplicationEvents} does not see.
     */
    static class UserActivatedEvents {

        private final List<UserActivatedEvent> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(UserActivatedEvent event) {
            received.add(event);
        }

        List<UserActivatedEvent> received() {
            return received;
        }
    }
}
