package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/** No registration owner configured: Google sign-up is closed with it, and an unconfigured Google answers 503. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.security.oauth.google.client-id=test-client.apps.googleusercontent.com",
        "flowcortex.security.oauth.google.client-secret=test-secret"})
@AutoConfigureTestRestTemplate
class GoogleOAuthRegistrationClosedIT extends AbstractGoogleOAuthIT {

    @Test
    void aNewGoogleEmailIsRefusedAndNoAccountIsCreated() {
        String email = uniqueEmail("google-closed");
        String sub = "sub-" + UUID.randomUUID();
        googleKnows(sub, email);
        String ip = newClientIp();
        Flow flow = authorize(null, false, ip);

        ResponseEntity<String> response = callback(CODE, flow.state(), null, flow.cookie(), ip);

        assertRefusedWith(response, "registration_closed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ? OR google_subject = ?",
                Integer.class, email, sub)).isZero();
        assertThat(auditCount("OAUTH_LOGIN_FAILED", "registration_closed")).isPositive();
    }

    @Test
    void authorizeIsA503ProblemWhenGoogleIsNotConfigured() {
        org.mockito.Mockito.doReturn(false).when(google).isConfigured();

        ResponseEntity<String> response = http.getForEntity("/api/v1/auth/oauth/google/authorize", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
