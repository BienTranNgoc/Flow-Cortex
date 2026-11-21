package com.flowcortex.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A provider with no key: the model test names the missing setting and sends nothing. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.llm.gemini.api-key=")
@AutoConfigureTestRestTemplate
class AiProviderUnconfiguredIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private UserService userService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken() {
        UserView admin = userService.createInitialAdmin("unconfigured-" + UUID.randomUUID() + "@example.com",
                PASSWORD, "unconfigured");
        ResponseEntity<Map> login = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", admin.email(), "password", PASSWORD), Map.class);
        return (String) login.getBody().get("accessToken");
    }

    @Test
    void aModelTestWithABlankKeyAnswersMissingConfigNamingTheSetting() {
        String token = adminToken();
        String key = "gemini-it-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange("/api/v1/admin/ai/models", HttpMethod.POST,
                new HttpEntity<>(Map.of("modelKey", key, "providerKey", "gemini", "displayName", "Unconfigured"),
                        headers), Map.class);
        try {
            ResponseEntity<Map> response = restTemplate.exchange("/api/v1/admin/ai/models/" + key + "/test",
                    HttpMethod.POST, new HttpEntity<>(null, headers), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("ok", false).containsEntry("tested", false)
                    .containsEntry("code", "missing_config");
            assertThat((List<String>) response.getBody().get("missingSettings")).containsExactly("GEMINI_API_KEY");
        } finally {
            jdbc.update("DELETE FROM llm_model WHERE model_key = ?", key);
        }
    }
}
