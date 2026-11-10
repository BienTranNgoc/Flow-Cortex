package com.flowcortex.app.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
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

/**
 * Default context: {@code flowcortex.onboarding.enabled} is not set, and absent means off. The
 * journey is closed and refuses progress writes, while the quiz keeps working.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@SuppressWarnings({"rawtypes", "unchecked"})
class OnboardingSwitchedOffIT extends AbstractPostgresBootTest {

    private static final String BASE = "/api/v1/onboarding";

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Test
    void switchOffClosesTheFlowRefusesProgressAndKeepsQuizWorking() {
        String token = token();

        Map<String, Object> status = call(HttpMethod.GET, "/status", token, null).getBody();
        ResponseEntity<Map> progress = call(HttpMethod.POST, "/progress", token, "{\"step\":\"welcome\"}");
        ResponseEntity<Map> finish = call(HttpMethod.POST, "/progress", token, "{\"finished\":true}");
        ResponseEntity<Map> quiz = call(HttpMethod.PATCH, "/quiz", token, "{\"aiLevel\":\"tried\"}");

        assertThat(status).containsEntry("open", false).containsEntry("entry", null);
        assertThat(progress.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(finish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(quiz.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> after = call(HttpMethod.GET, "/status", token, null).getBody();
        assertThat(after).containsEntry("aiLevel", "tried").containsEntry("progress", Map.of())
                .containsEntry("completed", false);
        assertThat(call(HttpMethod.POST, "/reset", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String token() {
        UserView user = userService.createInitialAdmin("off-" + UUID.randomUUID() + "@example.com", "password123",
                "Switch Off");
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(user.email(), "password123"),
                TokenResponse.class).getBody().accessToken();
    }

    private ResponseEntity<Map> call(HttpMethod method, String route, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(BASE + route, method, new HttpEntity<>(body, headers), Map.class);
    }
}
