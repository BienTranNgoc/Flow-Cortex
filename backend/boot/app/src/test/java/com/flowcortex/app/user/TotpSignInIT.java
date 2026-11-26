package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import com.flowcortex.user.application.GoogleIdTokenClaims;
import com.flowcortex.user.application.GoogleOAuthClient;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.totp.TotpCodes;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Signing in with a code over real HTTP and the real database: the challenge cookie, the code step for every
 * first factor, replay and lock rules, and the session mark that survives refresh. Time that has to pass is
 * moved on the test clock.
 */
@TotpIt
class TotpSignInIT extends AbstractTotpIT {

    private static final String COMPLETE = "/api/v1/auth/login/totp";
    private static final String GOOGLE_CODE = "google-code";
    private static final String FLOW_COOKIE = "oauth_flow";

    @Autowired
    private GoogleOAuthClient google;

    private void failTimes(Reply login, String wrong, int times) {
        for (int i = 0; i < times; i++) {
            Reply reply = complete(login, wrong);
            assertThat(reply.status()).as("wrong code %d: %s", i + 1, reply.body()).isEqualTo(422);
        }
    }

    /** Ten wrong codes over two challenges (a challenge dies at five); the tenth one locks the account. */
    private Reply lock(Enrolment enrolment) {
        String wrong = wrongCode(enrolment.secret());
        failTimes(challengedLogin(enrolment.account().email()), wrong, 5);
        Reply second = challengedLogin(enrolment.account().email());
        failTimes(second, wrong, 4);
        return complete(second, wrong);
    }

    @Test
    void twoStepSignIn() {
        Enrolment enrolment = enroll(member());

        Reply login = passwordLogin(enrolment.account().email(), true, false);

        assertThat(login.status()).isEqualTo(202);
        assertThat(login.text("status")).isEqualTo("totp_required");
        assertThat(login.body().get("expiresIn").asInt()).isEqualTo(300);
        assertThat(login.body().has("accessToken")).as("no token before the code").isFalse();
        assertThat(login.setCookie(REFRESH_COOKIE)).as("no session before the code").isNull();
        assertThat(login.setCookie(CHALLENGE_COOKIE)).contains("HttpOnly", "Secure", "SameSite=Strict",
                "Path=/api/v1/auth/login/totp", "Max-Age=300");

        Reply done = complete(login, currentCode(enrolment.secret()));

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().get("persistent").asBoolean()).isTrue();
        assertThat(hasMfaClaim(done.text("accessToken"))).isTrue();
        assertThat(done.setCookie(REFRESH_COOKIE)).contains("HttpOnly", "Secure", "SameSite=Lax",
                "Path=/api/v1/auth", "Max-Age=");
        assertThat(done.setCookie(CHALLENGE_COOKIE)).contains("Max-Age=0");
    }

    @Test
    void adminClientCookieNames() {
        Enrolment enrolment = enroll(member());

        Reply login = passwordLogin(enrolment.account().email(), false, true);

        assertThat(login.status()).isEqualTo(202);
        assertThat(login.setCookie("totp_challenge_admin")).isNotNull();
        assertThat(login.setCookie(CHALLENGE_COOKIE)).isNull();
        Reply done = call(HttpMethod.POST, COMPLETE, null, Map.of("code", currentCode(enrolment.secret())),
                Map.of("totp_challenge_admin", login.cookie("totp_challenge_admin")), true);
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.setCookie("refresh_token_admin")).isNotNull();
        assertThat(done.setCookie(REFRESH_COOKIE)).isNull();
    }

    @Test
    void expiredChallenge() {
        Enrolment enrolment = enroll(member());
        Reply login = challengedLogin(enrolment.account().email());
        clock.advance(Duration.ofMinutes(6));

        Reply done = complete(login, currentCode(enrolment.secret()));

        assertThat(done.status()).isEqualTo(401);
        assertThat(done.text("code")).isEqualTo("TOTP_CHALLENGE_INVALID");
        assertThat(done.setCookie(REFRESH_COOKIE)).isNull();
    }

    @Test
    void sixthCodeOnOneChallenge() {
        Enrolment enrolment = enroll(member());
        Reply login = challengedLogin(enrolment.account().email());
        failTimes(login, wrongCode(enrolment.secret()), 5);

        Reply sixth = complete(login, currentCode(enrolment.secret()));

        assertThat(sixth.status()).isEqualTo(401);
        assertThat(sixth.text("code")).isEqualTo("TOTP_CHALLENGE_INVALID");
        assertThat(sixth.setCookie(CHALLENGE_COOKIE)).contains("Max-Age=0");
        Reply fresh = challengedLogin(enrolment.account().email());
        assertThat(complete(fresh, currentCode(enrolment.secret())).status()).isEqualTo(200);
    }

    @Test
    void suspendedDuringChallenge() {
        Enrolment enrolment = enroll(member());
        Reply login = challengedLogin(enrolment.account().email());
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", enrolment.account().id());

        Reply done = complete(login, currentCode(enrolment.secret()));

        Reply passwordLogin = passwordLogin(enrolment.account().email(), false, false);
        assertThat(done.status()).as("the answer a suspended account gets at password login")
                .isEqualTo(passwordLogin.status()).isEqualTo(403);
        assertThat(done.setCookie(REFRESH_COOKIE)).isNull();
    }

    @Test
    void accountWithoutTotp() {
        Account plain = member();

        Reply login = passwordLogin(plain.email(), false, false);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("accessToken")).isNotBlank();
        assertThat(login.setCookie(CHALLENGE_COOKIE)).isNull();
        assertThat(login.setCookie(REFRESH_COOKIE)).isNotNull();
    }

    @Test
    void previousStepAccepted() {
        Enrolment enrolment = enroll(member());
        String previous = TotpCodes.codeAt(enrolment.secret(), TotpCodes.stepOf(clock.instant()) - 1);

        Reply done = complete(challengedLogin(enrolment.account().email()), previous);

        assertThat(done.status()).isEqualTo(200);
    }

    @Test
    void replayedCode() {
        Enrolment enrolment = enroll(member());
        String code = currentCode(enrolment.secret());
        assertThat(complete(challengedLogin(enrolment.account().email()), code).status()).isEqualTo(200);

        Reply replay = complete(challengedLogin(enrolment.account().email()), code);

        assertThat(replay.status()).isEqualTo(422);
        assertThat(replay.text("code")).isEqualTo("TOTP_INVALID_CODE");
        assertThat(totpRow(enrolment.account().id()).get("failed_attempts")).isEqualTo(1);
    }

    @Test
    void recoveryCodeTypedLoosely() {
        Enrolment enrolment = enroll(member());
        String loose = enrolment.recoveryCodes().get(0).toLowerCase().replace('-', ' ');

        Reply first = complete(challengedLogin(enrolment.account().email()), loose);
        Reply second = complete(challengedLogin(enrolment.account().email()), loose);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(422);
    }

    @Test
    void oneCodeTwoRequests() throws Exception {
        Enrolment enrolment = enroll(member());
        Reply onFirst = challengedLogin(enrolment.account().email());
        Reply onSecond = challengedLogin(enrolment.account().email());
        String code = currentCode(enrolment.secret());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Reply> first = pool.submit(completeOnGo(go, onFirst, code));
            Future<Reply> second = pool.submit(completeOnGo(go, onSecond, code));
            go.countDown();

            assertThat(List.of(first.get(60, TimeUnit.SECONDS).status(), second.get(60, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder(200, 422);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Reply> completeOnGo(CountDownLatch go, Reply login, String code) {
        return () -> {
            go.await();
            return complete(login, code);
        };
    }

    @Test
    void tenthWrongCode() {
        Enrolment enrolment = enroll(member());

        Reply tenth = lock(enrolment);

        assertThat(tenth.status()).isEqualTo(423);
        assertThat(tenth.text("code")).isEqualTo("TOTP_LOCKED");
        Instant lockedUntil = Instant.parse(tenth.text("lockedUntil"));
        assertThat(lockedUntil).isCloseTo(clock.instant().plus(15, ChronoUnit.MINUTES), within(10, ChronoUnit.SECONDS));
        assertThat(eventCount("TOTP_LOCKED", enrolment.account().id())).isEqualTo(1);
        assertThat(totpRow(enrolment.account().id())).containsEntry("failed_attempts", 0);
    }

    @Test
    void rightCodeWhileLocked() {
        Enrolment enrolment = enroll(member());
        lock(enrolment);
        Object stepBefore = totpRow(enrolment.account().id()).get("last_used_step");

        Reply done = complete(challengedLogin(enrolment.account().email()), currentCode(enrolment.secret()));

        assertThat(done.status()).isEqualTo(423);
        assertThat(done.text("code")).isEqualTo("TOTP_LOCKED");
        assertThat(totpRow(enrolment.account().id()).get("last_used_step")).isEqualTo(stepBefore);
        assertThat(done.setCookie(REFRESH_COOKIE)).isNull();
    }

    @Test
    void correctCodeResets() {
        Enrolment enrolment = enroll(member());
        Reply login = challengedLogin(enrolment.account().email());
        failTimes(login, wrongCode(enrolment.secret()), 4);
        assertThat(totpRow(enrolment.account().id()).get("failed_attempts")).isEqualTo(4);

        Reply done = complete(login, currentCode(enrolment.secret()));

        assertThat(done.status()).isEqualTo(200);
        assertThat(totpRow(enrolment.account().id()).get("failed_attempts")).isEqualTo(0);
    }

    @Test
    void refreshKeepsTheMark() {
        Enrolment enrolment = enroll(member());
        Reply done = complete(challengedLogin(enrolment.account().email()), currentCode(enrolment.secret()));
        clock.advance(Duration.ofMinutes(20));

        Reply refreshed = refresh(done.cookie(REFRESH_COOKIE));

        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(hasMfaClaim(refreshed.text("accessToken"))).isTrue();
    }

    @Test
    void passwordOnlySessionStaysUnmarked() {
        Session session = passwordSession(staff().email());

        Reply refreshed = refresh(session.refreshCookie());

        assertThat(hasMfaClaim(session.accessToken())).isFalse();
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(hasMfaClaim(refreshed.text("accessToken"))).isFalse();
    }

    @Test
    void setPasswordLinkOfATotpAccount() {
        Enrolment enrolment = enroll(member());
        UUID id = enrolment.account().id();
        jdbc.update("UPDATE app_user SET has_password = false, password_hash = '' WHERE id = ?", id);
        String rawLink = OpaqueTokens.generateRawToken();
        jdbc.update("INSERT INTO email_confirmation_token (id, user_id, token_hash, expires_at, created_at, purpose)"
                + " VALUES (?, ?, ?, ?, ?, 'PASSWORD_LINK')", UUID.randomUUID(), id, OpaqueTokens.hash(rawLink),
                Timestamp.from(clock.instant().plusSeconds(3600)), Timestamp.from(clock.instant()));

        Reply opened = post("/api/v1/auth/password-link", null, Map.of("token", rawLink));

        assertThat(opened.status()).isEqualTo(202);
        assertThat(opened.text("status")).isEqualTo("totp_required");
        assertThat(opened.body().get("passwordSetupRequired").asBoolean()).isTrue();
        String setupToken = opened.text("passwordSetupToken");
        assertThat(setupToken).isNotBlank();
        assertThat(opened.body().has("accessToken")).isFalse();
        assertThat(opened.setCookie(REFRESH_COOKIE)).isNull();
        Reply done = complete(opened, currentCode(enrolment.secret()));
        assertThat(done.status()).isEqualTo(200);
        Reply set = call(HttpMethod.PUT, "/api/v1/me/password", done.text("accessToken"),
                Map.of("passwordSetupToken", setupToken, "password", "brand-new-pass-1", "firstName", "Binh",
                        "lastName", "Le"), Map.of(), false);
        assertThat(set.status()).as("set password: %s", set.body()).isEqualTo(200);
        assertThat(hasMfaClaim(set.text("accessToken"))).as("the session keeps the caller's level").isTrue();
    }

    @Test
    void googleSignIn() {
        Enrolment enrolment = enroll(member());
        String sub = "sub-" + UUID.randomUUID();
        jdbc.update("UPDATE app_user SET google_subject = ? WHERE id = ?", sub, enrolment.account().id());
        doReturn(new GoogleIdTokenClaims(sub, enrolment.account().email(), true)).when(google)
                .exchangeAndVerify(eq(GOOGLE_CODE), anyString(), anyString());
        ResponseEntity<Void> authorized = http.exchange("/api/v1/auth/oauth/google/authorize?remember_me=true"
                + "&return_to=/workflows", HttpMethod.GET, new HttpEntity<>(headers(null)), Void.class);
        Map<String, String> query = queryOf(authorized.getHeaders().getLocation());
        String flowCookie = cookieOf(authorized, FLOW_COOKIE);

        ResponseEntity<String> callback = http.exchange("/api/v1/auth/oauth/google/callback?code=" + GOOGLE_CODE
                + "&state=" + query.get("state"), HttpMethod.GET,
                new HttpEntity<>(headers(FLOW_COOKIE + "=" + flowCookie)), String.class);

        assertThat(callback.getStatusCode().value()).isEqualTo(302);
        assertThat(callback.getHeaders().getLocation()).hasToString("/login/totp?return_to=/workflows");
        List<String> cookies = callback.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).noneMatch(cookie -> cookie.startsWith(REFRESH_COOKIE + "="));
        String challenge = cookies.stream().filter(cookie -> cookie.startsWith(CHALLENGE_COOKIE + "="))
                .findFirst().orElseThrow();
        assertThat(challenge).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth/login/totp");

        String challengeValue = challenge.substring(CHALLENGE_COOKIE.length() + 1, challenge.indexOf(';'));
        Reply done = call(HttpMethod.POST, COMPLETE, null, Map.of("code", currentCode(enrolment.secret())),
                Map.of(CHALLENGE_COOKIE, challengeValue), false);
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().get("persistent").asBoolean()).as("the flow's remember-me").isTrue();
        assertThat(hasMfaClaim(done.text("accessToken"))).isTrue();
        assertThat(eventCount("OAUTH_LOGIN_SUCCESS", enrolment.account().id())).isEqualTo(1);
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private static String cookieOf(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        String header = cookies.stream().filter(cookie -> cookie.startsWith(name + "=")).findFirst().orElseThrow();
        return header.substring(name.length() + 1, header.indexOf(';'));
    }

    private static Map<String, String> queryOf(URI uri) {
        Map<String, String> query = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] keyValue = pair.split("=", 2);
            query.put(keyValue[0], URLDecoder.decode(keyValue.length > 1 ? keyValue[1] : "", StandardCharsets.UTF_8));
        }
        return query;
    }
}
