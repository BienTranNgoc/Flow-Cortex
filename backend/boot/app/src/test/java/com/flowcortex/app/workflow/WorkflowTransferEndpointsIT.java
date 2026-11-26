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
                .containsEntry("allowFork", false).containsEntry("visibility", "private")
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

    private void putEntry(UUID workflowId, String key, String dataType, String json) {
        jdbc.update("INSERT INTO workflow_data_store (id, workflow_id, key, data_type, value, size_bytes,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, now(), now())",
                UUID.randomUUID(), workflowId, key, dataType, json, json.length());
    }

    private List<Map<String, Object>> entriesOf(UUID workflowId) {
        return jdbc.queryForList("SELECT key, data_type, value::text AS value FROM workflow_data_store "
                + "WHERE workflow_id = ? ORDER BY key", workflowId);
    }

    private void openForForking(UUID workflowId) {
        jdbc.update("UPDATE workflow SET allow_fork = true WHERE id = ?", workflowId);
    }

    private UUID insertCallStep(UUID workflowId, int order, UUID callee) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config) "
                + "VALUES (?, ?, ?, 'Call', 'workflow.call', ?::jsonb)", id, workflowId, order,
                "{\"workflowId\":\"" + callee + "\"}");
        return id;
    }

    @Test
    void aForkCarriesTheOwnersTemplateRowsOnlyAndNeverTheirPrivateOnes() {
        UserView ana = newUser("fork-rows-ana");
        UserView binh = userService.createUser(ana.id(), uniqueEmail("fork-rows-binh"), "password123", "Binh",
                "MEMBER");
        UUID workflowId = insertWorkflow(ana.id(), true, true, "With a store");
        openForForking(workflowId);
        putEntry(workflowId, "promo", "template", "{\"data\":{\"brief\":\"Spring sale\",\"price\":2.50}}");
        putEntry(workflowId, "apiNotes", "json", "{\"secret\":\"token-123\"}");

        ResponseEntity<Map> forked = exchange(HttpMethod.POST, "/api/v1/workflows/" + workflowId + "/fork",
                loginAs(binh), null);

        assertThat(forked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID forkId = UUID.fromString((String) forked.getBody().get("id"));
        assertThat(entriesOf(forkId)).hasSize(1);
        assertThat(entriesOf(forkId).get(0)).containsEntry("key", "promo").containsEntry("data_type", "template")
                .isEqualTo(entriesOf(workflowId).stream().filter(row -> "promo".equals(row.get("key"))).findFirst()
                        .orElseThrow());
        assertThat(entriesOf(workflowId)).hasSize(2);
    }

    @Test
    void aForkOfAWorkflowThatCallsOneTheForkerCannotSeeIsRefusedAndCreatesNothing() {
        UserView ana = newUser("fork-call-ana");
        UserView binh = userService.createUser(ana.id(), uniqueEmail("fork-call-binh"), "password123", "Binh",
                "MEMBER");
        UUID hidden = insertWorkflow(ana.id(), false, false, "Ana private child");
        UUID parent = insertWorkflow(ana.id(), true, true, "Parent");
        openForForking(parent);
        insertCallStep(parent, 2, hidden);
        String binhToken = loginAs(binh);

        ResponseEntity<Map> refused = exchange(HttpMethod.POST, "/api/v1/workflows/" + parent + "/fork", binhToken,
                null, "vi");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody()).containsEntry("code", "FORK_REFERENCE_UNRESOLVED");
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsOnlyKeys("step2.config");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow WHERE forked_from_id = ?", Integer.class,
                parent)).isZero();
        assertThat(jdbc.queryForObject("SELECT fork_count FROM workflow WHERE id = ?", Integer.class, parent))
                .isZero();

        jdbc.update("UPDATE workflow SET status = 'published', visibility = 'public' WHERE id = ?", hidden);
        assertThat(exchange(HttpMethod.POST, "/api/v1/workflows/" + parent + "/fork", binhToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anExportedWorkflowCarriesItsRowsThroughReviewAndImport() {
        UserView admin = newUser("transfer-rows");
        UUID workflowId = insertWorkflow(admin.id(), false, false, "Dub with store");
        putEntry(workflowId, "promo", "template", "{\"data\":{\"brief\":\"Spring sale\",\"price\":2.50}}");
        putEntry(workflowId, "notes", "json", "[1,\"two\",null]");
        String token = loginAs(admin);

        String file = exchange(HttpMethod.GET, ADMIN + "/" + workflowId + "/export", token, null, String.class)
                .getBody();
        assertThat(file).contains("\"dataStore\"");
        ResponseEntity<Map> review = review(token, file, null);
        assertThat(review.getBody()).containsEntry("blocked", false);
        ResponseEntity<Map> created = exchange(HttpMethod.POST, ADMIN + "/import", token,
                Map.of("token", review.getBody().get("token")));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID importedId = UUID.fromString((String) created.getBody().get("id"));
        assertThat(entriesOf(importedId)).isEqualTo(entriesOf(workflowId));
        assertThat(entriesOf(importedId)).hasSize(2);
    }

    @Test
    void aFileWithoutADataStoreSectionStillImportsWithAnEmptyStore() {
        UserView admin = newUser("transfer-no-rows");
        String token = loginAs(admin);
        String file = "{\"formatVersion\":1,\"exportedAt\":\"2026-11-09T10:00:00Z\",\"workflow\":{\"name\":\"Plain\"},"
                + "\"steps\":[{\"stepOrder\":1,\"name\":null,\"type\":\"echo\",\"inputRef\":null,\"config\":null}]}";

        ResponseEntity<Map> review = review(token, file, null);
        ResponseEntity<Map> created = exchange(HttpMethod.POST, ADMIN + "/import", token,
                Map.of("token", review.getBody().get("token")));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entriesOf(UUID.fromString((String) created.getBody().get("id")))).isEmpty();
    }

    @Test
    void aRowTheStoreRefusesBlocksTheReviewAndTheImportCreatesNothing() {
        UserView admin = newUser("transfer-refused-row");
        String token = loginAs(admin);
        String name = "Refused row " + UUID.randomUUID();
        String file = "{\"formatVersion\":1,\"exportedAt\":\"2026-11-09T10:00:00Z\",\"workflow\":{\"name\":\"" + name
                + "\"},\"steps\":[{\"stepOrder\":1,\"name\":null,\"type\":\"echo\",\"inputRef\":null,"
                + "\"config\":null}],\"dataStore\":[{\"key\":\"bad key!\",\"dataType\":\"template\",\"value\":{}}]}";

        ResponseEntity<Map> review = review(token, file, "vi");

        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review.getBody()).containsEntry("blocked", true);
        List<Map<String, Object>> issues = (List<Map<String, Object>>) review.getBody().get("issues");
        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue).containsEntry("code", "data_store_entry_invalid").containsEntry("severity", "blocking")
                    .containsEntry("field", "dataStore[0].key");
            assertThat(String.valueOf(issue.get("message"))).contains("Khóa");
        });
        ResponseEntity<Map> blocked = exchange(HttpMethod.POST, ADMIN + "/import", token,
                Map.of("token", review.getBody().get("token")));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(blocked.getBody()).containsEntry("code", "IMPORT_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow WHERE name = ?", Integer.class, name)).isZero();
    }

    @Test
    void aCallStepNamingAWorkflowTheAdminCannotSeeBlocksTheReviewAndTheImport() {
        UserView admin = newUser("transfer-call");
        UserView other = userService.createUser(admin.id(), uniqueEmail("transfer-call-other"), "password123",
                "Other", "MEMBER");
        UUID hidden = insertWorkflow(other.id(), false, false, "Someone else's");
        UUID visible = insertWorkflow(admin.id(), false, false, "Mine");
        String token = loginAs(admin);

        for (UUID callee : List.of(UUID.randomUUID(), hidden)) {
            ResponseEntity<Map> review = review(token, callFile(callee), null);
            assertThat(review.getBody()).containsEntry("blocked", true);
            assertThat((List<Map<String, Object>>) review.getBody().get("issues")).singleElement().satisfies(issue ->
                    assertThat(issue).containsEntry("code", "reference_unresolved")
                            .containsEntry("severity", "blocking").containsEntry("stepOrder", 1)
                            .containsEntry("field", "config"));
            ResponseEntity<Map> blocked = exchange(HttpMethod.POST, ADMIN + "/import", token,
                    Map.of("token", review.getBody().get("token")));
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(blocked.getBody()).containsEntry("code", "IMPORT_BLOCKED");
        }

        ResponseEntity<Map> clean = review(token, callFile(visible), null);
        assertThat(clean.getBody()).containsEntry("blocked", false);
        assertThat(exchange(HttpMethod.POST, ADMIN + "/import", token, Map.of("token", clean.getBody().get("token")))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static String callFile(UUID callee) {
        return "{\"formatVersion\":1,\"exportedAt\":\"2026-11-09T10:00:00Z\",\"workflow\":{\"name\":\"Caller\"},"
                + "\"steps\":[{\"stepOrder\":1,\"name\":null,\"type\":\"workflow.call\",\"inputRef\":null,"
                + "\"config\":{\"workflowId\":\"" + callee + "\"}}]}";
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
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
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
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, visibility, created_at, updated_at, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, ownerId, name, shared, shared ? "public" : "private", now, now,
                published ? "published" : "draft");
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type) "
                + "VALUES (?, ?, 1, 'First', 'echo')", UUID.randomUUID(), id);
        return id;
    }
}
