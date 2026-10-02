package com.flowcortex.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies an unauthenticated request is rejected with a 401 {@code application/problem+json}
 * body, rendered via :shared:security's {@code AuthenticationEntryPoint} delegation. Exercised
 * against the real, fully-wired filter chain — not a {@code @WebMvcTest} slice.
 *
 * <p>Lives under {@code com.flowcortex.app}, like {@code HealthController} — a direct
 * sub-package of {@code com.flowcortex} would become a stray Modulith module.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SecurityFilterChainIT extends AbstractPostgresBootTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void unauthenticatedRequestReturns401ProblemDetail() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).contains("\"status\":401");
    }

    @Test
    void theReadinessProbeIsReachableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/readyz", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
