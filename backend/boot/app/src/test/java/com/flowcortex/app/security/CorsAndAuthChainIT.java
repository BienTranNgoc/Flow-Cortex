package com.flowcortex.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * One configured CORS origin list serves every security chain, and the auth chain's anonymous
 * endpoints match the refresh-cookie contract. Runs against the real, fully-wired filter chains.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"flowcortex.security.cors.allowed-origins=https://app.example.test",
                "flowcortex.security.cors.allow-credentials=true"})
@AutoConfigureTestRestTemplate
class CorsAndAuthChainIT extends AbstractPostgresBootTest {

    private static final String ALLOWED_ORIGIN = "https://app.example.test";
    private static final String OTHER_ORIGIN = "https://evil.example.test";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void authChainPreflightFromConfiguredOriginGetsCorsHeaders() {
        ResponseEntity<String> response = preflight("/api/v1/auth/login", ALLOWED_ORIGIN);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void authChainPreflightFromUnlistedOriginGetsNoCorsHeaders() {
        ResponseEntity<String> response = preflight("/api/v1/auth/login", OTHER_ORIGIN);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void adminChainPreflightFromConfiguredOriginGetsCorsHeaders() {
        ResponseEntity<String> response = preflight("/api/v1/admin/runs", ALLOWED_ORIGIN);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void defaultChainPreflightFromConfiguredOriginGetsCorsHeaders() {
        ResponseEntity<String> response = preflight("/api/v1/health", ALLOWED_ORIGIN);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void logoutWithoutAnAccessTokenIsNotRejectedByTheChain() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/logout", null, String.class);

        assertThat(response.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void profileStillRequiresAnAccessToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/auth/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> preflight(String path, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of("content-type"));
        return restTemplate.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }
}
