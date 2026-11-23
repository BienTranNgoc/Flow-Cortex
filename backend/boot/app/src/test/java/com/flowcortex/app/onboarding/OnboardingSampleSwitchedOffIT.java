package com.flowcortex.app.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Default context, journey switch off: a sample still saves, reports {@code onboarding_off} and is not offered. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@SuppressWarnings({"rawtypes", "unchecked"})
class OnboardingSampleSwitchedOffIT extends AbstractPostgresBootTest {

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void switchOffSavesAndReportsOnboardingOff() {
        jdbc.update("UPDATE onboarding_sample SET workflow_id = NULL, sample_input = NULL, source_locale = NULL "
                + "WHERE id = 1");
        UserView admin = userService.createInitialAdmin("admin-" + UUID.randomUUID() + "@example.com", "password123",
                "admin");
        String token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(admin.email(), "password123"), TokenResponse.class).getBody().accessToken();
        UUID workflow = workflowService.createWorkflow(admin.id(), "Caption", true,
                List.of(new NewWorkflowStep("Echo", "echo", null))).id();

        ResponseEntity<Map> saved = call(HttpMethod.PUT, "/api/v1/admin/onboarding/sample", token,
                "{\"workflowId\":\"" + workflow + "\",\"sampleInput\":\"hello\",\"sourceLocale\":\"en\"}");

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).containsEntry("shown", false).containsEntry("reason", "onboarding_off");
        assertThat(call(HttpMethod.GET, "/api/v1/onboarding/status", token, null).getBody())
                .containsEntry("sampleWorkflow", null);
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
