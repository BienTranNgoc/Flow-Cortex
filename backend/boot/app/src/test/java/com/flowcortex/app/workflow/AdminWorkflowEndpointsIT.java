package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code /api/v1/admin/workflows}: the ADMIN-only write surface. Only the authorization rule is
 * checked here; the shared write behavior is covered through the member surface and the service.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminWorkflowEndpointsIT extends AbstractWorkflowHttpIT {

    private static final UUID NIL_OWNER = new UUID(0L, 0L);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMemberAndAnAnonymousCallerAreRefusedOnEveryAdminWorkflowRoute() {
        UserView admin = newUser("admin-wf-refusal");
        UserView member = userService.createUser(admin.id(), uniqueEmail("member-wf"), "password123", "Member",
                "MEMBER");
        UUID workflowId = insertWorkflow(admin.id(), "draft", false);
        UUID stepId = insertStep(workflowId);
        String memberToken = loginAs(member);

        for (Route route : routes(workflowId, stepId)) {
            assertThat(send(route, memberToken).getStatusCode()).as("member " + route).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(send(route, null).getStatusCode()).as("anonymous " + route).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(jdbc.queryForObject("SELECT name FROM workflow WHERE id = ?", String.class, workflowId))
                .isEqualTo("Fixture");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM workflow WHERE id = ?", Timestamp.class, workflowId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_step WHERE workflow_id = ?", Integer.class,
                workflowId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM workflow_step WHERE id = ?", String.class, stepId))
                .isEqualTo("Fixture step");
    }

    @Test
    void anAdminEditsPublishesAndDeletesAnotherOwnersWorkflow() {
        UserView admin = newUser("admin-wf-edit");
        UserView owner = newUser("owner-wf-edit");
        UUID published = insertWorkflow(owner.id(), "published", true);
        UUID draft = insertWorkflow(owner.id(), "draft", true);
        UUID nilOwned = insertWorkflow(NIL_OWNER, "published", true);
        String token = loginAs(admin);

        ResponseEntity<Map> renamed = exchange(HttpMethod.PATCH, "/api/v1/admin/workflows/" + published, token,
                Map.of("name", "Renamed by admin"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody()).containsEntry("name", "Renamed by admin").containsEntry("status", "published");

        ResponseEntity<Map> unpublished = exchange(HttpMethod.PATCH, "/api/v1/admin/workflows/" + published, token,
                Map.of("status", "draft"));
        assertThat(unpublished.getBody()).containsEntry("status", "draft");

        ResponseEntity<Map> publishedAgain = exchange(HttpMethod.PATCH, "/api/v1/admin/workflows/" + draft, token,
                Map.of("status", "published"));
        assertThat(publishedAgain.getBody()).containsEntry("status", "published");

        assertThat(exchange(HttpMethod.DELETE, "/api/v1/admin/workflows/" + published, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/admin/workflows/" + published, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(exchange(HttpMethod.DELETE, "/api/v1/admin/workflows/" + nilOwned, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/admin/workflows/" + nilOwned, token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void everyAdminWriteToTheProductVideoWorkflowIsAConflict() {
        UserView admin = newUser("admin-wf-system");
        String token = loginAs(admin);
        UUID workflowId = ProductVideoWorkflow.ID;
        UUID stepId = jdbc.queryForObject(
                "SELECT id FROM workflow_step WHERE workflow_id = ? ORDER BY step_order LIMIT 1", UUID.class,
                workflowId);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM workflow_step WHERE workflow_id = ? "
                + "ORDER BY step_order", workflowId);
        Map<String, Object> workflowBefore = jdbc.queryForMap("SELECT * FROM workflow WHERE id = ?", workflowId);

        for (Route route : routes(workflowId, stepId)) {
            if (route.method() == HttpMethod.POST && route.path().endsWith("/admin/workflows")) {
                continue;
            }
            ResponseEntity<Map> response = exchange(route.method(), route.path(), token, route.body());
            assertThat(response.getStatusCode()).as(route.toString()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(String.valueOf(response.getBody().get("detail"))).as(route.toString())
                    .isEqualTo("This is a built-in workflow and cannot be changed");
        }

        assertThat(jdbc.queryForMap("SELECT * FROM workflow WHERE id = ?", workflowId)).isEqualTo(workflowBefore);
        assertThat(jdbc.queryForList("SELECT * FROM workflow_step WHERE workflow_id = ? ORDER BY step_order",
                workflowId)).isEqualTo(before);
    }

    private record Route(HttpMethod method, String path, Object body) {
    }

    private static List<Route> routes(UUID workflowId, UUID stepId) {
        String base = "/api/v1/admin/workflows";
        return List.of(
                new Route(HttpMethod.POST, base, Map.of("name", "Created by admin")),
                new Route(HttpMethod.PATCH, base + "/" + workflowId, Map.of("name", "Changed")),
                new Route(HttpMethod.DELETE, base + "/" + workflowId, null),
                new Route(HttpMethod.POST, base + "/" + workflowId + "/steps", Map.of("type", "echo")),
                new Route(HttpMethod.PATCH, base + "/" + workflowId + "/steps/" + stepId, Map.of("name", "Changed")));
    }

    private ResponseEntity<Map> send(Route route, String token) {
        return exchange(route.method(), route.path(), token, route.body());
    }

    private ResponseEntity<Map> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private UUID insertWorkflow(UUID ownerId, String status, boolean shared) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status) "
                + "VALUES (?, ?, 'Fixture', ?, ?, ?, ?)", id, ownerId, shared, now, now, status);
        return id;
    }

    private UUID insertStep(UUID workflowId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type) "
                + "VALUES (?, ?, 1, 'Fixture step', 'echo')", id, workflowId);
        return id;
    }
}
