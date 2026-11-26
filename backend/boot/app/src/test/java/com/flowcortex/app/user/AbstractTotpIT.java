package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.user.totp.TotpCodes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Support of the TOTP ITs: a plain HTTP client (no cookie jar, no redirects, so every Set-Cookie and Location
 * stays visible and cookies are sent by hand), accounts, the password sign-in, enrolment and codes computed with
 * {@link TotpCodes} at the test clock's instant. Each case starts at the real time with the requirement on.
 */
abstract class AbstractTotpIT extends AbstractUserModuleIT {

    protected static final String PASSWORD = "correct-password-1";
    protected static final String REFRESH_COOKIE = "refresh_token";
    protected static final String CHALLENGE_COOKIE = "totp_challenge";
    protected static final String ADMIN_CLIENT = "X-FlowCortex-Client";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MovableClock clock;

    @Autowired
    protected TestTotpRequirement requirement;

    protected TestRestTemplate http;
    protected String clientIp;

    /** An account and the admin that owns it; for a console account both are the same user. */
    protected record Account(UserView admin, UserView user, String email) {
        UUID id() {
            return user.id();
        }
    }

    /** A signed-in session: the bearer token and the raw refresh cookie value. */
    protected record Session(String accessToken, String refreshCookie) {
    }

    /** An account with TOTP on: its secret, its recovery codes and the session enable opened. */
    protected record Enrolment(Account account, String secret, List<String> recoveryCodes, Session session) {
    }

    /** One HTTP answer, with the JSON body parsed (empty object when there is none). */
    protected record Reply(int status, JsonNode body, HttpHeaders headers) {

        String text(String field) {
            JsonNode node = body.get(field);
            return node == null || node.isNull() ? null : node.asString();
        }

        String setCookie(String name) {
            List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
            return cookies == null ? null
                    : cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
        }

        String cookie(String name) {
            String header = setCookie(name);
            return header == null ? null : header.substring(name.length() + 1, header.indexOf(';'));
        }
    }

    @BeforeEach
    void resetTimeAndRequirement() {
        clock.reset();
        requirement.reset();
    }

    @BeforeEach
    void plainHttpClient() {
        http = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .redirects(HttpRedirects.DONT_FOLLOW)
                .rootUri(restTemplate.getRootUri()));
        clientIp = "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    protected Reply call(HttpMethod method, String path, String bearer, Object body, Map<String, String> cookies,
            boolean adminClient) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (adminClient) {
            headers.set(ADMIN_CLIENT, "admin");
        }
        if (!cookies.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            cookies.forEach((name, value) -> pairs.add(name + "=" + value));
            headers.set(HttpHeaders.COOKIE, String.join("; ", pairs));
        }
        ResponseEntity<String> response = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        String text = response.getBody();
        JsonNode json = text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        return new Reply(response.getStatusCode().value(), json, response.getHeaders());
    }

    protected Reply post(String path, String bearer, Object body) {
        return call(HttpMethod.POST, path, bearer, body, Map.of(), false);
    }

    protected Reply get(String path, String bearer) {
        return call(HttpMethod.GET, path, bearer, null, Map.of(), false);
    }

    /** A member owned by a fresh admin. */
    protected Account member() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("totp-admin"), PASSWORD, "Admin");
        String email = uniqueEmail("totp-member");
        return new Account(admin, userService.createUser(admin.id(), email, PASSWORD, "Member", "MEMBER"), email);
    }

    /** A staff account (a console account) owned by a fresh admin. */
    protected Account staff() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("totp-admin"), PASSWORD, "Admin");
        String email = uniqueEmail("totp-staff");
        return new Account(admin,
                userService.createUser(admin.id(), email, PASSWORD, "Linh", "CUSTOMER_SUPPORT"), email);
    }

    /** A super admin: the console account of the initial admin. */
    protected Account admin() {
        String email = uniqueEmail("totp-root");
        UserView admin = userService.createInitialAdmin(email, PASSWORD, "Root");
        return new Account(admin, admin, email);
    }

    protected Reply passwordLogin(String email, boolean rememberMe, boolean adminClient) {
        return call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("email", email, "password", PASSWORD, "rememberMe", rememberMe), Map.of(), adminClient);
    }

    /** Signs in with the password of an account without TOTP; fails the case on anything but a session. */
    protected Session passwordSession(String email) {
        Reply reply = passwordLogin(email, false, false);
        assertThat(reply.status()).as("password login of %s: %s", email, reply.body()).isEqualTo(200);
        return new Session(reply.text("accessToken"), reply.cookie(REFRESH_COOKIE));
    }

    protected Reply refresh(String refreshCookie) {
        return call(HttpMethod.POST, "/api/v1/auth/refresh", null, null, Map.of(REFRESH_COOKIE, refreshCookie), false);
    }

    protected Reply setup(Session session) {
        return post("/api/v1/auth/totp/setup", session.accessToken(), null);
    }

    protected Reply enable(Session session, String code) {
        return call(HttpMethod.POST, "/api/v1/auth/totp/enable", session.accessToken(), Map.of("code", code),
                Map.of(REFRESH_COOKIE, session.refreshCookie()), false);
    }

    /**
     * Turns TOTP on through the real endpoints and moves the clock a minute on, so every later code is of a
     * later step than the one enable accepted.
     */
    protected Enrolment enroll(Account account) {
        Session before = passwordSession(account.email());
        Reply setup = setup(before);
        assertThat(setup.status()).as("setup: %s", setup.body()).isEqualTo(200);
        String secret = setup.text("secret");
        Reply enabled = enable(before, currentCode(secret));
        assertThat(enabled.status()).as("enable: %s", enabled.body()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        enabled.body().get("recoveryCodes").forEach(code -> codes.add(code.asString()));
        Session session = new Session(enabled.text("accessToken"), enabled.cookie(REFRESH_COOKIE));
        clock.advance(Duration.ofSeconds(61));
        return new Enrolment(account, secret, codes, session);
    }

    protected String currentCode(String secret) {
        return TotpCodes.codeAt(secret, TotpCodes.stepOf(clock.instant()));
    }

    /** A code of a step later than any this test has used so far. */
    protected String freshCode(String secret) {
        clock.advance(Duration.ofSeconds(31));
        return currentCode(secret);
    }

    /** A six-digit code that is wrong for the secret in the previous, current and next step. */
    protected String wrongCode(String secret) {
        long step = TotpCodes.stepOf(clock.instant());
        List<String> valid = new ArrayList<>();
        for (long s = step - 2; s <= step + 2; s++) {
            valid.add(TotpCodes.codeAt(secret, s));
        }
        for (int candidate = 0; ; candidate++) {
            String code = "%06d".formatted(candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
    }

    /** The password step of a sign-in that is expected to answer 202, with its challenge cookie. */
    protected Reply challengedLogin(String email) {
        Reply reply = passwordLogin(email, false, false);
        assertThat(reply.status()).as("password login: %s", reply.body()).isEqualTo(202);
        return reply;
    }

    /** The code step, with the challenge cookie of {@code login} and no bearer token. */
    protected Reply complete(Reply login, String code) {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put(CHALLENGE_COOKIE, login.cookie(CHALLENGE_COOKIE));
        return call(HttpMethod.POST, "/api/v1/auth/login/totp", null, Map.of("code", code), cookies, false);
    }

    /** The claims of an access token (read without verifying; the server already did). */
    protected static JsonNode claims(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(payload)));
    }

    protected static boolean hasMfaClaim(String accessToken) {
        JsonNode mfa = claims(accessToken).get("mfa");
        return mfa != null && mfa.asBoolean();
    }

    protected Map<String, Object> totpRow(UUID userId) {
        return jdbc.queryForMap("SELECT secret_encrypted, enabled_at, last_used_step, failed_attempts, locked_until"
                + " FROM user_totp WHERE user_id = ?", userId);
    }

    protected int eventCount(String type, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE type = ? AND user_id = ?", Integer.class,
                type, userId);
    }

    protected int recoveryCodesInDatabase(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_recovery_code WHERE user_id = ?", Integer.class, userId);
    }
}
