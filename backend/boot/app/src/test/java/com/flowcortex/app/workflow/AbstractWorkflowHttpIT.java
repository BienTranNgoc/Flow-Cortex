package com.flowcortex.app.workflow;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Shared HTTP-level test support for the workflow endpoint ITs — mirrors
 * {@code AbstractUserModuleIT}'s role for {@code :modules:user}'s endpoints.
 * Real login over HTTP (not a hand-minted token) so the same JWT the running
 * app actually issues is exercised end to end.
 */
public abstract class AbstractWorkflowHttpIT extends AbstractWorkflowModuleIT {

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String login(String email, String password) {
        ResponseEntity<TokenResponse> response =
                restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password),
                        TokenResponse.class);
        TokenResponse body = response.getBody();
        return body != null ? body.accessToken() : null;
    }

    /** Logs in as a freshly-seeded user (via {@code newUser}) and returns its token. */
    protected String loginAs(UserView user) {
        String token = login(user.email(), "password123");
        if (token == null) {
            throw new IllegalStateException("Login failed for seeded user " + user.email());
        }
        return token;
    }

    protected <T> ResponseEntity<T> get(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, bearerEntity(token, null), responseType);
    }

    protected <T> ResponseEntity<T> post(String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, bearerEntity(token, body), responseType);
    }

    private static HttpEntity<Object> bearerEntity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record StartRunRequestView(String input) {
    }

    record WorkflowStepResponseView(UUID id, int stepOrder, String name, String type, String mode) {
    }

    record WorkflowResponseView(
            UUID id, String name, boolean isShared, Instant createdAt, Instant updatedAt,
            List<WorkflowStepResponseView> steps) {
    }

    record RunStepResponseView(
            UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
            Instant startedAt, Instant finishedAt, String mode) {
    }

    record RunResponseView(
            UUID id, UUID workflowId, String status, String input, String errorMessage, Instant startedAt,
            Instant finishedAt, List<RunStepResponseView> steps) {
    }
}
