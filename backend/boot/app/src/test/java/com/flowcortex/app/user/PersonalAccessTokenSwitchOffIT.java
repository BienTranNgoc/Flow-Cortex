package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.application.OpaqueTokens;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the switch off the token routes answer 503, a stored token is refused with 401, and /features says so. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "flowcortex.auth.personal-access-tokens.enabled=false")
@AutoConfigureTestRestTemplate
class PersonalAccessTokenSwitchOffIT extends AbstractUserModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    private TestRestTemplate client() {
        return new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk()).rootUri(restTemplate.getRootUri()));
    }

    private ResponseEntity<String> call(String path, HttpMethod method, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return client().exchange(path, method, new HttpEntity<>(Map.of("name", "x"), headers), String.class);
    }

    @Test
    void tokenRoutesAnswer503AStoredTokenAnswers401AndFeaturesReportsOff() {
        String email = uniqueEmail("switch-off");
        UUID id = userService.createInitialAdmin(email, "switch-off-password", "Switch Off").id();
        String session = login(email, "switch-off-password");
        String raw = "fc_pat_" + OpaqueTokens.generateRawToken();
        jdbc.update("INSERT INTO personal_access_token (id, user_id, name, token_hash, created_at, expires_at)"
                + " VALUES (?, ?, 'stored', ?, now(), now() + interval '30 days')", UUID.randomUUID(), id,
                OpaqueTokens.hash(raw));

        assertThat(call("/api/v1/me/api-tokens", HttpMethod.GET, session).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call("/api/v1/me/api-tokens", HttpMethod.POST, session).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call("/api/v1/me/api-tokens/" + UUID.randomUUID(), HttpMethod.DELETE, session).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call("/api/v1/workflows", HttpMethod.GET, raw).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForObject("/api/v1/features", Map.class)).containsEntry("personalAccessTokens",
                false);
    }
}
