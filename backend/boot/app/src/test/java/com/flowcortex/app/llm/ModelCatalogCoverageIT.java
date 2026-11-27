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

/**
 * The catalog's capability rows match what the workflow engine runs, on the full context: the engine declares
 * its step types at start-up and the list marks every seeded row as run by it, with nothing left over.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ModelCatalogCoverageIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private UserService userService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken() {
        UserView admin = userService.createInitialAdmin("coverage-" + UUID.randomUUID() + "@example.com", PASSWORD,
                "coverage");
        ResponseEntity<Map> login = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", admin.email(), "password", PASSWORD), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) login.getBody().get("accessToken");
    }

    @Test
    void everySeededCapabilityIsRunByTheEngineAndNoRunnableTypeLacksARow() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());

        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/admin/ai/capabilities", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) response.getBody().get("capabilities");
        List<String> rows = jdbc.queryForList("SELECT capability FROM capability_catalog", String.class);
        assertThat(capabilities).extracting(capability -> capability.get("capability"))
                .containsExactlyInAnyOrderElementsOf(rows);
        assertThat(capabilities).isNotEmpty().allSatisfy(capability -> assertThat(capability)
                .containsEntry("registered", true));
        assertThat((List<String>) response.getBody().get("uncatalogued")).isEmpty();
    }
}
