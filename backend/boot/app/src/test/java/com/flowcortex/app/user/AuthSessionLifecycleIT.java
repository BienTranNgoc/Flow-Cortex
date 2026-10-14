package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.user.application.AuthRetentionJob;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Password login, refresh rotation, logout and their failure paths over real HTTP against the
 * fully-wired app. Each request names its client through {@code X-Forwarded-For} so every test
 * has its own per-IP login throttle bucket.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.registration.owner-email=" + UserAuthEndpointsIT.OWNER_EMAIL)
@AutoConfigureTestRestTemplate
class AuthSessionLifecycleIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final int MAX_ACTIVE_FAMILIES = 10;
    private static final int LOGIN_FAILURES_PER_IP = 20;
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthRetentionJob retentionJob;

    /** The default Apache client keeps its own cookie jar and sleeps through a 429's Retry-After. */
    private TestRestTemplate http;

    record Session(String accessToken, Long expiresIn, String refreshToken) {
    }

    record Member(UserView admin, UserView member, String email) {
    }

    @BeforeEach
    void plainHttpClient() {
        http = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
    }

    private static String newClientIp() {
        return "203.0.113." + NEXT_CLIENT.getAndIncrement();
    }

    private Member newMember(String prefix) {
        UserView admin = userService.createInitialAdmin(uniqueEmail(prefix + "-admin"), PASSWORD, "Admin");
        String email = uniqueEmail(prefix);
        UserView member = userService.createUser(admin.id(), email, PASSWORD, "Member", "MEMBER");
        return new Member(admin, member, email);
    }

    private <T> ResponseEntity<T> postJson(String path, Object body, String clientIp, String cookie,
            Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + cookie);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    private ResponseEntity<Session> login(String email, String password, boolean rememberMe, String clientIp) {
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", password, "rememberMe", rememberMe),
                clientIp, null, Session.class);
    }

    private ResponseEntity<String> loginFailing(String email, String password, String clientIp) {
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", password), clientIp, null,
                String.class);
    }

    private static String setCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie").isNotNull();
        return cookies.stream().filter(c -> c.startsWith(REFRESH_COOKIE + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no refresh_token cookie in " + cookies));
    }

    private static String cookieValue(ResponseEntity<?> response) {
        String header = setCookie(response);
        return header.substring((REFRESH_COOKIE + "=").length(), header.indexOf(';'));
    }

    private static long maxAge(String setCookie) {
        return java.util.Arrays.stream(setCookie.split(";\\s*"))
                .filter(attribute -> attribute.startsWith("Max-Age="))
                .map(attribute -> Long.parseLong(attribute.substring("Max-Age=".length())))
                .findFirst().orElse(-1L);
    }

    private Map<String, Object> userRow(String email) {
        return jdbc.queryForMap("SELECT failed_login_attempts, locked_until FROM app_user WHERE email = ?", email);
    }

    private int auditCount(String type, String email) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM auth_event e LEFT JOIN app_user u ON u.id = e.user_id
                WHERE e.type = ? AND (u.email = ? OR e.metadata->>'email' = ?)
                """, Integer.class, type, email, email);
    }

    private int activeRefreshTokens(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL"
                + " AND used_at IS NULL", Integer.class, userId);
    }

    @Test
    void aLockedAccountAnswersTheSame423ToARightAndAWrongPasswordAndTheWindowIsNotExtended() {
        Member m = newMember("lockout");
        String ip = newClientIp();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(loginFailing(m.email(), "wrong-" + attempt, ip).getStatusCode()).as("attempt %d", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        Map<String, Object> locked = userRow(m.email());
        assertThat(((Number) locked.get("failed_login_attempts")).intValue()).isEqualTo(5);
        assertThat(locked.get("locked_until")).isNotNull();
        ResponseEntity<String> wrongWhileLocked = loginFailing(m.email(), "wrong-6", ip);
        ResponseEntity<String> rightWhileLocked = loginFailing(m.email(), PASSWORD, ip);
        assertThat(wrongWhileLocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(rightWhileLocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(rightWhileLocked.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(rightWhileLocked.getBody()).isEqualTo(wrongWhileLocked.getBody()).contains("lockedUntil");
        assertThat(userRow(m.email())).isEqualTo(locked);
        assertThat(auditCount("LOGIN_FAILED_BAD_CREDENTIALS", m.email())).isEqualTo(5);
        assertThat(auditCount("LOGIN_FAILED_ACCOUNT_LOCKED", m.email())).isEqualTo(2);

        jdbc.update("UPDATE app_user SET locked_until = now() - interval '1 minute' WHERE email = ?", m.email());

        assertThat(login(m.email(), PASSWORD, false, ip).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) userRow(m.email()).get("failed_login_attempts")).intValue()).isZero();
    }

    @Test
    void aWrongPasswordAfterTheLockWindowEndsStartsANewCountInsteadOfAnswering423() {
        Member m = newMember("lockout-expired");
        String ip = newClientIp();
        for (int attempt = 1; attempt <= 5; attempt++) {
            loginFailing(m.email(), "wrong-" + attempt, ip);
        }
        jdbc.update("UPDATE app_user SET locked_until = now() - interval '1 minute' WHERE email = ?", m.email());

        assertThat(loginFailing(m.email(), "wrong-after-window", ip).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((Number) userRow(m.email()).get("failed_login_attempts")).intValue()).isEqualTo(1);
    }

    private ResponseEntity<String> postAs(String client, String path, Object body, String ip, String cookieName,
            String cookieValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (client != null) {
            headers.set("X-FlowCortex-Client", client);
        }
        if (cookieValue != null) {
            headers.set(HttpHeaders.COOKIE, cookieName + "=" + cookieValue);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String cookieNamed(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie").isNotNull();
        String header = cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in " + cookies));
        return header.substring((name + "=").length(), header.indexOf(';'));
    }

    @Test
    void adminAndMemberAppsKeepIndependentRefreshSessionsUnderTheirOwnCookieNames() {
        Member m = newMember("per-app-cookie");
        String ip = newClientIp();
        Map<String, Object> credentials = Map.of("email", m.email(), "password", PASSWORD);

        ResponseEntity<String> adminLogin = postAs("admin", "/api/v1/auth/login", credentials, ip, null, null);
        ResponseEntity<String> memberLogin = postAs(null, "/api/v1/auth/login", credentials, ip, null, null);

        assertThat(adminLogin.getHeaders().get(HttpHeaders.SET_COOKIE)).hasSize(1)
                .allMatch(c -> c.startsWith("refresh_token_admin="));
        assertThat(memberLogin.getHeaders().get(HttpHeaders.SET_COOKIE)).hasSize(1)
                .allMatch(c -> c.startsWith("refresh_token="));
        String adminCookie = cookieNamed(adminLogin, "refresh_token_admin");
        String memberCookie = cookieNamed(memberLogin, "refresh_token");

        ResponseEntity<String> adminRefresh = postAs("admin", REFRESH, null, ip, "refresh_token_admin", adminCookie);
        assertThat(adminRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cookieNamed(adminRefresh, "refresh_token_admin")).isNotEqualTo(adminCookie);
        String rotatedAdmin = cookieNamed(adminRefresh, "refresh_token_admin");

        assertThat(postAs("admin", LOGOUT, null, ip, "refresh_token_admin", rotatedAdmin).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(postAs("admin", REFRESH, null, ip, "refresh_token_admin", rotatedAdmin).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> memberRefresh = postAs(null, REFRESH, null, ip, "refresh_token", memberCookie);
        assertThat(memberRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postAs("admin", REFRESH, null, ip, "refresh_token", memberCookie).getStatusCode())
                .as("admin app never reads the member cookie").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postAs(null, REFRESH, null, ip, "refresh_token_admin", rotatedAdmin).getStatusCode())
                .as("member app never reads the admin cookie").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownClientHeaderValueFallsBackToTheMemberCookie() {
        Member m = newMember("per-app-unknown");

        ResponseEntity<String> response = postAs("something-else", "/api/v1/auth/login",
                Map.of("email", m.email(), "password", PASSWORD), newClientIp(), null, null);

        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).hasSize(1)
                .allMatch(c -> c.startsWith("refresh_token="));
    }

    @Test
    void aSuccessfulLoginResetsTheFailedAttemptCounter() {
        Member m = newMember("lockout-reset");
        String ip = newClientIp();
        loginFailing(m.email(), "wrong-1", ip);
        loginFailing(m.email(), "wrong-2", ip);
        assertThat(((Number) userRow(m.email()).get("failed_login_attempts")).intValue()).isEqualTo(2);

        assertThat(login(m.email(), PASSWORD, false, ip).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(((Number) userRow(m.email()).get("failed_login_attempts")).intValue()).isZero();
        assertThat(auditCount("LOGIN_SUCCESS", m.email())).isEqualTo(1);
    }

    @Test
    void suspendedWithAWrongPasswordLooksLikeAnyOtherWrongPasswordAndOnlyTheRightOneIs403() {
        Member m = newMember("enumeration");
        String ip = newClientIp();
        Member active = newMember("enumeration-active");
        userService.updateUser(m.admin().id(), m.member().id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> suspendedWrong = loginFailing(m.email(), "wrong-password-1", ip);
        ResponseEntity<String> activeWrong = loginFailing(active.email(), "wrong-password-1", ip);
        ResponseEntity<String> unknown = loginFailing(uniqueEmail("nobody"), "wrong-password-1", ip);

        assertThat(suspendedWrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(suspendedWrong.getBody()).isEqualTo(activeWrong.getBody()).isEqualTo(unknown.getBody());
        assertThat(loginFailing(m.email(), PASSWORD, ip).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(auditCount("LOGIN_FAILED_ACCOUNT_SUSPENDED", m.email())).isEqualTo(1);
    }

    @Test
    void loginAnswersTheAccessTokenOnlyAndPutsTheRefreshTokenInAnHttpOnlyCookie() {
        Member m = newMember("login-shape");

        ResponseEntity<String> response = postJson("/api/v1/auth/login",
                Map.of("email", m.email(), "password", PASSWORD), newClientIp(), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"accessToken\"").contains("\"expiresIn\"")
                .doesNotContain("refreshToken");
        String cookie = setCookie(response);
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
                .contains("Path=/api/v1/auth");
    }

    @Test
    void rememberMeGivesAThirtyDayCookieAndNoRememberMeGivesASessionCookie() {
        Member m = newMember("cookie-persistence");
        String ip = newClientIp();

        String remembered = setCookie(login(m.email(), PASSWORD, true, ip));
        String session = setCookie(login(m.email(), PASSWORD, false, ip));

        long thirtyDays = 30L * 24 * 3600;
        assertThat(maxAge(remembered)).isBetween(thirtyDays - 60, thirtyDays);
        assertThat(maxAge(session)).isEqualTo(-1L);
    }

    @Test
    void refreshRotatesTheCookieKeepsTheFamilyLifetimeAndItsPersistence() {
        Member m = newMember("rotation");
        String ip = newClientIp();
        ResponseEntity<Session> first = login(m.email(), PASSWORD, true, ip);
        String firstCookie = cookieValue(first);
        Long expiryBefore = jdbc.queryForObject("SELECT extract(epoch FROM expires_at)::bigint FROM refresh_token"
                + " WHERE user_id = ?", Long.class, m.member().id());

        ResponseEntity<String> refreshed = postJson(REFRESH, null, ip, firstCookie, String.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).contains("\"accessToken\"").contains("\"expiresIn\"")
                .doesNotContain("refreshToken");
        assertThat(cookieValue(refreshed)).isNotEqualTo(firstCookie);
        long remaining = maxAge(setCookie(refreshed));
        assertThat(remaining).isPositive().isLessThanOrEqualTo(30L * 24 * 3600);
        List<Long> expiries = jdbc.queryForList("SELECT extract(epoch FROM expires_at)::bigint FROM refresh_token"
                + " WHERE user_id = ?", Long.class, m.member().id());
        assertThat(expiries).hasSize(2).containsOnly(expiryBefore);
        assertThat(get("/api/v1/auth/me", first.getBody().accessToken(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSessionFamilyStaysASessionCookieAfterRotation() {
        Member m = newMember("rotation-session");
        String ip = newClientIp();
        String cookie = cookieValue(login(m.email(), PASSWORD, false, ip));

        ResponseEntity<String> refreshed = postJson(REFRESH, null, ip, cookie, String.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(maxAge(setCookie(refreshed))).isEqualTo(-1L);
    }

    @Test
    void reusingARotatedTokenAnswers401RevokesTheWholeFamilyAndClearsTheCookie() {
        Member m = newMember("reuse");
        String ip = newClientIp();
        String original = cookieValue(login(m.email(), PASSWORD, false, ip));
        String child = cookieValue(postJson(REFRESH, null, ip, original, String.class));

        ResponseEntity<String> reuse = postJson(REFRESH, null, ip, original, String.class);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(maxAge(setCookie(reuse))).isZero();
        assertThat(postJson(REFRESH, null, ip, child, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(activeRefreshTokens(m.member().id())).isZero();
        assertThat(auditCount("REFRESH_TOKEN_REUSE_DETECTED", m.email())).isEqualTo(1);
    }

    @Test
    void aGarbageOrMissingRefreshTokenAnswers401NotAServerError() {
        String ip = newClientIp();

        ResponseEntity<String> garbage = postJson(REFRESH, null, ip, "not-a-real-token", String.class);
        ResponseEntity<String> missing = postJson(REFRESH, null, ip, null, String.class);

        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(garbage.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(maxAge(setCookie(garbage))).isZero();
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshIgnoresATokenSentInTheBody() {
        Member m = newMember("refresh-body");
        String ip = newClientIp();
        String cookie = cookieValue(login(m.email(), PASSWORD, false, ip));

        ResponseEntity<String> response = postJson(REFRESH, Map.of("refreshToken", cookie), ip, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshAfterSuspensionAnswers401AndRevokesTheSessions() {
        Member m = newMember("suspend-refresh");
        String ip = newClientIp();
        String cookie = cookieValue(login(m.email(), PASSWORD, true, ip));

        userService.updateUser(m.admin().id(), m.member().id(), null, null, null, "SUSPENDED");

        assertThat(activeRefreshTokens(m.member().id())).isZero();
        assertThat(postJson(REFRESH, null, ip, cookie, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutWorksWithoutAnAccessTokenRevokesTheFamilyAndClearsTheCookie() {
        Member m = newMember("logout");
        String ip = newClientIp();
        String cookie = cookieValue(login(m.email(), PASSWORD, true, ip));

        ResponseEntity<String> logout = postJson(LOGOUT, null, ip, cookie, String.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(maxAge(setCookie(logout))).isZero();
        assertThat(postJson(REFRESH, null, ip, cookie, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auditCount("LOGOUT", m.email())).isEqualTo(1);
        assertThat(postJson(LOGOUT, null, ip, null, String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void theOldestFamiliesBeyondTheActiveCapAreRevoked() {
        Member m = newMember("cap");
        String ip = newClientIp();
        String oldest = cookieValue(login(m.email(), PASSWORD, false, ip));
        for (int i = 0; i < MAX_ACTIVE_FAMILIES; i++) {
            assertThat(login(m.email(), PASSWORD, false, ip).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(activeRefreshTokens(m.member().id())).isEqualTo(MAX_ACTIVE_FAMILIES);
        assertThat(postJson(REFRESH, null, ip, oldest, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theRetentionJobDeletesOldAuditRowsAndDeadRefreshTokensButKeepsTheLiveOnes() {
        Member m = newMember("retention");
        login(m.email(), PASSWORD, false, newClientIp());
        UUID oldEvent = UUID.randomUUID();
        UUID recentEvent = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_event (id, type, occurred_at) VALUES (?, 'LOGOUT', now() - interval '100 days')",
                oldEvent);
        jdbc.update("INSERT INTO auth_event (id, type, occurred_at) VALUES (?, 'LOGOUT', now() - interval '1 day')",
                recentEvent);
        UUID expired = UUID.randomUUID();
        jdbc.update("INSERT INTO refresh_token (id, user_id, token_hash, family_id, issued_at, expires_at)"
                + " VALUES (?, ?, ?, ?, now() - interval '9 days', now() - interval '2 days')",
                expired, m.member().id(), "expired-" + expired, UUID.randomUUID());

        retentionJob.purge();

        assertThat(jdbc.queryForList("SELECT id FROM auth_event WHERE id IN (?, ?)", UUID.class, oldEvent,
                recentEvent)).containsExactly(recentEvent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE id = ?", Integer.class, expired))
                .isZero();
        assertThat(activeRefreshTokens(m.member().id())).isEqualTo(1);
    }

    @Test
    void failedLoginsFromOneIpAreThrottledWith429AndRetryAfter() {
        String ip = newClientIp();
        for (int attempt = 1; attempt <= LOGIN_FAILURES_PER_IP; attempt++) {
            assertThat(loginFailing(uniqueEmail("nobody"), "wrong-password-1", ip).getStatusCode())
                    .as("attempt %d", attempt).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> throttled = loginFailing(uniqueEmail("nobody"), "wrong-password-1", ip);

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(Long.parseLong(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(loginFailing(uniqueEmail("nobody"), "wrong-password-1", newClientIp()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
