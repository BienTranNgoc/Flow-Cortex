package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/** The fork route, the fork switch and the admin export, review and import routes, over HTTP. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowTransferEndpointsIT extends AbstractWorkflowHttpIT {

    private static final String ADMIN = "/api/v1/admin/workflows";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMemberAndAnAnonymousCallerAreRefusedOnTheTransferRoutes() {
        UserView admin = newUser("transfer-refusal-admin");
        UserView member = userService.createUser(admin.id(), uniqueEmail("transfer-member"), "password123", "Member",
                "MEMBER");
        UUID workflowId = insertWorkflow(admin.id(), true, true, "Mover");
        String memberToken = loginAs(member);

        for (Route route : List.of(
                new Route(HttpMethod.GET, ADMIN + "/" + workflowId + "/export", null),
                new Route(HttpMethod.POST, ADMIN + "/import/review", "{}"),
                new Route(HttpMethod.POST, ADMIN + "/import", Map.of("token", UUID.randomUUID().toString())))) {
            assertThat(send(route, memberToken).getStatusCode()).as("member " + route)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(route, null).getStatusCode()).as("anonymous " + route)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(exchange(HttpMethod.POST, "/api/v1/workflows/" + workflowId + "/fork", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMemberForksOnceTheOwnerSwitchesForkingOn() {
        UserView ana = newUser("fork-ana");
        UserView binh = userService.createUser(ana.id(), uniqueEmail("fork-binh"), "password123", "Binh", "MEMBER");
        UUID workflowId = insertWorkflow(ana.id(), true, true, "Ana flow");
        String anaToken = loginAs(ana);
        String binhToken = loginAs(binh);
        String fork = "/api/v1/workflows/" + workflowId + "/fork";

        assertThat(exchange(HttpMethod.POST, fork, binhToken, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> own = exchange(HttpMethod.POST, fork, anaToken, null);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(own.getBody()).containsEntry("code", "CANNOT_FORK_OWN");

        ResponseEntity<Map> switched = exchange(HttpMethod.PATCH, "/api/v1/workflows/" + workflowId, anaToken,
                Map.of("allowFork", true));
        assertThat(switched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(switched.getBody()).containsEntry("allowFork", true).containsEntry("forkCount", 0);

        ResponseEntity<Map> forked = exchange(HttpMethod.POST, fork, binhToken, null);
        assertThat(forked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(forked.getBody()).containsEntry("forkedFromId", workflowId.toString())
                .containsEntry("allowFork", false).containsEntry("isShared", false)
                .containsEntry("status", "draft");
        assertThat(jdbc.queryForObject("SELECT fork_count FROM workflow WHERE id = ?", Integer.class, workflowId))
                .isEqualTo(1);
    }

    @Test
    void anAdminExportsReviewsAndImportsAWorkflowAndAReplayAnswersTheSameOne() {
        UserView admin = newUser("transfer-admin");
        UUID workflowId = insertWorkflow(admin.id(), false, false, "Daily recap");
        String token = loginAs(admin);

        ResponseEntity<String> exported = exchange(HttpMethod.GET, ADMIN + "/" + workflowId + "/export", token, null,
                String.class);
        assertThat(exported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exported.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("daily-recap.workflow.json");
        assertThat(exported.getHeaders().getCacheControl()).contains("no-store");

        ResponseEntity<Map> review = review(token, exported.getBody(), null);
        assertThat(review.getStatusCode()).as(String.valueOf(review.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(review.getBody()).containsEntry("workflowName", "Daily recap").containsEntry("blocked", false)
                .containsEntry("outbound", false).containsEntry("stepCount", 1);
        String reviewToken = (String) review.getBody().get("token");

        ResponseEntity<Map> created = exchange(HttpMethod.POST, ADMIN + "/import", token,
                Map.of("token", reviewToken));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("name", "Daily recap").containsEntry("allowFork", false);

        ResponseEntity<Map> replay = exchange(HttpMethod.POST, ADMIN + "/import", token,
                Map.of("token", reviewToken));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("id", created.getBody().get("id"));
    }

    @Test
    void aFileOverTwoMebibytesIsRefusedAsProblemJsonAndAnUnknownKeyAsInvalid() {
        UserView admin = newUser("transfer-limits");
        String token = loginAs(admin);

        ResponseEntity<Map> tooLarge = review(token, " ".repeat(2 * 1024 * 1024 + 1), null);
        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(tooLarge.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();

        ResponseEntity<Map> invalid = review(token, "{\"formatVersion\":1,\"exportedAt\":\"2026-11-09T10:00:00Z\","
                + "\"workflow\":{\"name\":\"X\"},\"steps\":[],\"ownerId\":\"x\"}", "vi");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(invalid.getBody()).containsEntry("code", "ARTIFACT_INVALID");
        assertThat(String.valueOf(invalid.getBody().get("detail"))).isEqualTo("Tệp quy trình không hợp lệ");
        assertThat(invalid.getBody().get("errors")).isEqualTo(Map.of("ownerId", "Trường không được hỗ trợ"));
        assertThat(invalid.getBody().get("reasons")).isEqualTo(Map.of("ownerId", "unknown_key"));
    }

    @Test
    void aTokenOfAnotherAdminAnsweredAsExpiredInTheCallersLanguage() {
        UserView admin = newUser("transfer-expired");
        String token = loginAs(admin);

        ResponseEntity<Map> response = exchange(HttpMethod.POST, ADMIN + "/import", token,
                Map.of("token", UUID.randomUUID().toString()), "vi");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("code", "IMPORT_TOKEN_EXPIRED");
        assertThat(String.valueOf(response.getBody().get("detail"))).contains("hết hạn");
    }

    private ResponseEntity<Map> review(String token, String body, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return restTemplate.exchange(ADMIN + "/import/review", HttpMethod.POST, new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> exchange(HttpMethod method, String path, String token, Object body) {
        return exchange(method, path, token, body, Map.class);
    }

    private ResponseEntity<Map> exchange(HttpMethod method, String path, String token, Object body, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, String token, Object body,
            Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<Map> send(Route route, String token) {
        return exchange(route.method(), route.path(), token, route.body());
    }

    private record Route(HttpMethod method, String path, Object body) {
    }

    private UUID insertWorkflow(UUID ownerId, boolean shared, boolean published, String name) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, ownerId, name, shared, now, now,
                published ? "published" : "draft");
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type) "
                + "VALUES (?, ?, 1, 'First', 'echo')", UUID.randomUUID(), id);
        return id;
    }
}
