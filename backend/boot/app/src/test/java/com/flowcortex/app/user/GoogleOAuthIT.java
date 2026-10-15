package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.registration.owner-email=" + GoogleOAuthIT.OWNER_EMAIL,
        "flowcortex.security.oauth.google.client-id=test-client.apps.googleusercontent.com",
        "flowcortex.security.oauth.google.client-secret=test-secret",
        "flowcortex.security.oauth.google.redirect-uri=http://localhost:5173/api/v1/auth/oauth/google/callback"})
@AutoConfigureTestRestTemplate
class GoogleOAuthIT extends AbstractGoogleOAuthIT {

    static final String OWNER_EMAIL = "google-oauth-owner@example.com";
    private static final String PASSWORD = "correct-password-1";

    /** The registration owner, created on first use because the database outlives a single test. */
    private UUID owner() {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class,
                OWNER_EMAIL);
        if (existing == 0) {
            return userService.createInitialAdmin(OWNER_EMAIL, PASSWORD, "Google Owner").id();
        }
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, OWNER_EMAIL);
    }

    @Test
    void authorizeRedirectsToGoogleWithPkceAndSealsTheFlowInAnHttpOnlyCookie() {
        Flow flow = authorize("/runs", false, newClientIp());

        assertThat(flow.response().getStatusCode().value()).isEqualTo(302);
        URI location = flow.response().getHeaders().getLocation();
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        Map<String, String> query = queryOf(location);
        assertThat(query).containsEntry("code_challenge_method", "S256").containsEntry("state", flow.state())
                .containsEntry("redirect_uri", "http://localhost:5173/api/v1/auth/oauth/google/callback");
        assertThat(query.get("nonce")).isNotBlank();
        assertThat(query.get("code_challenge")).isNotBlank();
        String cookie = setCookieHeader(flow.response(), FLOW_COOKIE);
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/auth/oauth/google",
                "Max-Age=600");
    }

    @Test
    void aNewGoogleEmailSignsUpUnderTheRegistrationOwnerAndTheSessionCookieIsSetWithoutATokenInTheUrl() {
        UUID ownerId = owner();
        String email = uniqueEmail("google-new");
        String sub = "sub-" + UUID.randomUUID();
        googleKnows(sub, email.toUpperCase());
        String ip = newClientIp();
        Flow flow = authorize("/runs?tab=2", true, ip);

        ResponseEntity<String> response = callback(CODE, flow.state(), null, flow.cookie(), ip);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        String location = response.getHeaders().getLocation().toString();
        assertThat(location).isEqualTo("/runs?tab=2");
        assertThat(location.toLowerCase()).doesNotContain("token");
        String refresh = setCookieHeader(response, REFRESH_COOKIE);
        assertThat(refresh).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/auth", "Max-Age=");
        assertThat(setCookieHeader(response, FLOW_COOKIE)).contains("Max-Age=0");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT owner_id, has_password, google_subject, email, role FROM app_user WHERE google_subject = ?",
                sub);
        assertThat(row).containsEntry("owner_id", ownerId).containsEntry("has_password", false)
                .containsEntry("email", email).containsEntry("role", "MEMBER");
        assertThat(auditCount("OAUTH_LOGIN_SUCCESS", null)).isPositive();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + cookieValue(response, REFRESH_COOKIE));
        ResponseEntity<Map> refreshed = http.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
        assertThat(refreshed.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(refreshed.getBody()).containsKey("accessToken");
    }

    @Test
    void aReturningGoogleUserIsMatchedBySubject() {
        owner();
        String email = uniqueEmail("google-again");
        String sub = "sub-" + UUID.randomUUID();
        googleKnows(sub, email);
        String ip = newClientIp();
        Flow first = authorize(null, false, ip);
        callback(CODE, first.state(), null, first.cookie(), ip);

        Flow second = authorize(null, false, ip);
        ResponseEntity<String> response = callback(CODE, second.state(), null, second.cookie(), ip);

        assertThat(response.getHeaders().getLocation()).hasToString("/");
        assertThat(cookieValue(response, REFRESH_COOKIE)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE google_subject = ?", Integer.class, sub))
                .isEqualTo(1);
    }

    @Test
    void anEmailThatAlreadyHasAnAccountRequiresALinkAndIsNeverLinked() {
        UUID adminId = owner();
        String email = uniqueEmail("google-existing");
        UserView member = userService.createUser(adminId, email, PASSWORD, "Existing", "MEMBER");
        googleKnows("sub-" + UUID.randomUUID(), email.toUpperCase());
        String ip = newClientIp();
        Flow flow = authorize(null, false, ip);

        ResponseEntity<String> response = callback(CODE, flow.state(), null, flow.cookie(), ip);

        assertRefusedWith(response, "link_required");
        assertThat(jdbc.queryForObject("SELECT google_subject FROM app_user WHERE id = ?", String.class, member.id()))
                .isNull();
        assertThat(auditCount("OAUTH_LOGIN_FAILED", "link_required")).isPositive();
    }

    @Test
    void aSuspendedLinkedUserIsAccountInactive() {
        UserView member = userService.createUser(owner(), uniqueEmail("google-suspended"), PASSWORD, "Susp",
                "MEMBER");
        String sub = "sub-" + UUID.randomUUID();
        jdbc.update("UPDATE app_user SET google_subject = ?, status = 'SUSPENDED' WHERE id = ?", sub, member.id());
        googleKnows(sub, member.email());
        String ip = newClientIp();
        Flow flow = authorize(null, false, ip);

        ResponseEntity<String> response = callback(CODE, flow.state(), null, flow.cookie(), ip);

        assertRefusedWith(response, "account_inactive");
        assertThat(auditCount("OAUTH_LOGIN_FAILED", "account_inactive")).isPositive();
    }

    @Test
    void aCallbackWithoutTheFlowCookieOrWithAnotherStateIsInvalidState() {
        googleKnows("sub-" + UUID.randomUUID(), uniqueEmail("google-csrf"));
        String ip = newClientIp();
        Flow flow = authorize(null, false, ip);

        assertRefusedWith(callback(CODE, flow.state(), null, null, ip), "invalid_state");
        assertRefusedWith(callback(CODE, "another-state", null, flow.cookie(), ip), "invalid_state");
        assertRefusedWith(callback(CODE, null, null, flow.cookie(), ip), "invalid_state");
        String forged = flow.cookie().substring(0, flow.cookie().length() - 4) + "AAAA";
        assertRefusedWith(callback(CODE, flow.state(), null, forged, ip), "invalid_state");
        assertThat(auditCount("OAUTH_LOGIN_FAILED", "invalid_state")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aCancelledConsentIsReportedAsCancelled() {
        String ip = newClientIp();
        Flow flow = authorize(null, false, ip);

        ResponseEntity<String> response = callback(null, flow.state(), "access_denied", flow.cookie(), ip);

        assertRefusedWith(response, "cancelled");
        assertThat(auditCount("OAUTH_LOGIN_FAILED", "cancelled")).isPositive();
    }

    @Test
    void aGoogleFailureIsReportedAsFailed() {
        org.mockito.Mockito.doThrow(new com.flowcortex.user.application.OAuthVerificationException("down"))
                .when(google).exchangeAndVerify(org.mockito.ArgumentMatchers.eq(CODE),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        String ip = newClientIp();
        Flow flow = authorize(null, false, ip);

        assertRefusedWith(callback(CODE, flow.state(), null, flow.cookie(), ip), "failed");
    }

    @Test
    void anUnsafeReturnToFallsBackToTheRoot() {
        owner();
        googleKnows("sub-" + UUID.randomUUID(), uniqueEmail("google-return"));
        String ip = newClientIp();
        Flow flow = authorize("//evil.example", false, ip);

        ResponseEntity<String> response = callback(CODE, flow.state(), null, flow.cookie(), ip);

        assertThat(response.getHeaders().getLocation()).hasToString("/");
    }
}
