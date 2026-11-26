package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.application.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Writes made with a personal access token over real HTTP and the real database: one audit row per
 * write, the optional daily caps and the {@code Idempotency-Key} replay. Both caps are on (total 6,
 * workflow creations 4) and every test uses an owner of its own.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "flowcortex.auth.personal-access-tokens.enabled=true",
        "flowcortex.auth.api-token-calls.daily-write-limit=6",
        "flowcortex.auth.api-token-calls.daily-workflow-create-limit=4"})
@AutoConfigureTestRestTemplate
class ApiTokenCallGuardsIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "api-token-calls-123";
    private static final String WORKFLOWS = "/api/v1/workflows";

    @Autowired
    private JdbcTemplate jdbc;

    record Owner(UUID id, String session, String token, UUID tokenId) {
    }

    record ProofView(String stepUpProof) {
    }

    record CreatedToken(UUID id, String token) {
    }

    record WorkflowView(UUID id, String name) {
    }

    record ProblemView(int status, String type, String code) {
    }

    /** The JDK client hands a 401 back as is and does not retry it. */
    private TestRestTemplate client() {
        return new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk()).rootUri(restTemplate.getRootUri()));
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, String bearer, Object body, Class<T> type,
            String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.ALL));
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.set(headerPairs[i], headerPairs[i + 1]);
        }
        return client().exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private Owner owner(String prefix) {
        String email = uniqueEmail(prefix);
        UUID id = userService.createInitialAdmin(email, PASSWORD, "Call Admin").id();
        String session = login(email, PASSWORD);
        String proof = exchange("/api/v1/me/step-up", HttpMethod.POST, session,
                Map.of("password", PASSWORD, "purpose", "PAT_CREATE"), ProofView.class).getBody().stepUpProof();
        CreatedToken created = exchange("/api/v1/me/api-tokens", HttpMethod.POST, session,
                Map.of("name", "MCP: test", "stepUpProof", proof), CreatedToken.class).getBody();
        return new Owner(id, session, created.token(), created.id());
    }

    private ResponseEntity<WorkflowView> createWorkflow(Owner owner, String name, String... headerPairs) {
        return exchange(WORKFLOWS, HttpMethod.POST, owner.token(), Map.of("name", name), WorkflowView.class,
                headerPairs);
    }

    private List<Map<String, Object>> calls(Owner owner) {
        return jdbc.queryForList("SELECT * FROM api_token_call WHERE user_id = ? ORDER BY occurred_at", owner.id());
    }

    private long workflowsNamed(Owner owner, String name) {
        WorkflowView[] all = exchange(WORKFLOWS, HttpMethod.GET, owner.token(), null, WorkflowView[].class).getBody();
        return Arrays.stream(all).filter(workflow -> name.equals(workflow.name())).count();
    }

    @Test
    void aSuccessfulStepWriteIsRecordedUnderItsNormalizedRoute() {
        Owner owner = owner("call-ok");
        UUID workflow = createWorkflow(owner, "Steps").getBody().id();

        ResponseEntity<String> step = exchange(WORKFLOWS + "/" + workflow + "/steps", HttpMethod.POST, owner.token(),
                Map.of("type", "echo"), String.class);

        assertThat(step.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = calls(owner).stream()
                .filter(call -> "/api/v1/workflows/{id}/steps".equals(call.get("route"))).findFirst().orElseThrow();
        assertThat(row.get("user_id")).isEqualTo(owner.id());
        assertThat(row.get("token_id")).isEqualTo(owner.tokenId());
        assertThat(row.get("method")).isEqualTo("POST");
        assertThat(row.get("outcome")).isEqualTo("ok");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(201);
        assertThat(row.get("error_code")).isNull();
        assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aFailedWriteIsRecordedWithItsErrorCode() {
        Owner owner = owner("call-failed");

        ResponseEntity<String> missing = exchange(WORKFLOWS + "/" + UUID.randomUUID(), HttpMethod.DELETE,
                owner.token(), null, String.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> row = calls(owner).getFirst();
        assertThat(row.get("route")).isEqualTo("/api/v1/workflows/{id}");
        assertThat(row.get("outcome")).isEqualTo("error");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(404);
        assertThat((String) row.get("error_code")).isNotBlank().doesNotStartWith("http-");
    }

    @Test
    void aWriteOutsideTheAllowlistIsRecordedAsNotAllowed() {
        Owner owner = owner("call-denied");

        ResponseEntity<ProblemView> denied = exchange("/api/v1/admin/workflows", HttpMethod.POST, owner.token(),
                Map.of("name", "Nope"), ProblemView.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> row = calls(owner).getFirst();
        assertThat(row.get("outcome")).isEqualTo("error");
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(403);
        assertThat(row.get("error_code")).isEqualTo("pat.notAllowed");
    }

    @Test
    void sessionWritesTokenReadsAndUnknownTokensWriteNoRow() {
        Owner owner = owner("call-silent");

        assertThat(exchange(WORKFLOWS, HttpMethod.POST, owner.session(), Map.of("name", "By session"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchange(WORKFLOWS, HttpMethod.GET, owner.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> unknown = exchange(WORKFLOWS, HttpMethod.POST,
                "fc_pat_" + OpaqueTokens.generateRawToken(), Map.of("name", "X"), String.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(calls(owner)).isEmpty();
    }

    @Test
    void noColumnHoldsTheQueryOrTheBody() {
        Owner owner = owner("call-private");
        UUID workflow = createWorkflow(owner, "Private").getBody().id();

        // The row's uuids are hex, so the sentinel carries non-hex letters and cannot match one by chance.
        exchange(WORKFLOWS + "/" + workflow + "?secret=qzx-query", HttpMethod.PATCH, owner.token(),
                Map.of("name", "Bí mật"), String.class);

        String everything = jdbc.queryForList("SELECT row_to_json(c)::text FROM api_token_call c WHERE user_id = ?",
                String.class, owner.id()).toString();
        assertThat(everything).doesNotContain("secret").doesNotContain("qzx-query").doesNotContain("Bí mật");
    }

    @Test
    void aFailedRowInsertLeavesTheAnswerAsTheHandlerGaveIt(CapturedOutput output) {
        Owner owner = owner("call-insert-fails");
        jdbc.execute("ALTER TABLE api_token_call RENAME TO api_token_call_off");
        ResponseEntity<WorkflowView> created;
        try {
            created = createWorkflow(owner, "Survives");
        } finally {
            jdbc.execute("ALTER TABLE api_token_call_off RENAME TO api_token_call");
        }

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().name()).isEqualTo("Survives");
        assertThat(output.getAll()).contains("API token call row for POST /api/v1/workflows was not recorded");
        assertThat(output.getAll()).doesNotContain(owner.token());
    }

    @Test
    void overTheCapsTheWriteIsRefusedBlockedAndItsHandlerDoesNotRun() {
        Owner owner = owner("call-cap");
        for (int i = 1; i <= 4; i++) {
            assertThat(createWorkflow(owner, "Capped " + i).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<ProblemView> fifth = exchange(WORKFLOWS, HttpMethod.POST, owner.token(),
                Map.of("name", "Capped 5"), ProblemView.class);

        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fifth.getBody().code()).isEqualTo("API_TOKEN_DAILY_LIMIT");
        assertThat(Long.parseLong(fifth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 86_400L);
        assertThat(workflowsNamed(owner, "Capped 5")).isZero();

        UUID workflow = createdWorkflowId(owner, "Capped 1");
        assertThat(patch(owner, workflow, "Renamed 1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch(owner, workflow, "Renamed 2").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> overTotal = patch(owner, workflow, "Renamed 3");

        assertThat(overTotal.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(workflowsNamed(owner, "Renamed 3")).isZero();
        assertThat(calls(owner)).filteredOn(call -> "blocked".equals(call.get("outcome"))).hasSize(2)
                .allSatisfy(call -> {
                    assertThat(((Number) call.get("http_status")).intValue()).isEqualTo(429);
                    assertThat(call.get("error_code")).isEqualTo("apiToken.dailyLimit");
                });
        assertThat(exchange(WORKFLOWS, HttpMethod.POST, owner.session(), Map.of("name", "By session"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> patch(Owner owner, UUID workflow, String name) {
        return exchange(WORKFLOWS + "/" + workflow, HttpMethod.PATCH, owner.token(), Map.of("name", name),
                String.class);
    }

    private UUID createdWorkflowId(Owner owner, String name) {
        return Arrays.stream(exchange(WORKFLOWS, HttpMethod.GET, owner.token(), null, WorkflowView[].class)
                .getBody()).filter(workflow -> name.equals(workflow.name())).findFirst().orElseThrow().id();
    }

    @Test
    void aKeyedCreateRunsOnceAndItsRetryIsReplayed() {
        Owner owner = owner("call-replay");

        ResponseEntity<String> first = exchange(WORKFLOWS, HttpMethod.POST, owner.token(), Map.of("name", "Promo"),
                String.class, "Idempotency-Key", "create-42");
        ResponseEntity<String> retry = exchange(WORKFLOWS, HttpMethod.POST, owner.token(), Map.of("name", "Promo"),
                String.class, "Idempotency-Key", "  create-42  ");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(workflowsNamed(owner, "Promo")).isEqualTo(1);
        assertThat(calls(owner)).extracting(call -> call.get("outcome")).containsExactly("ok", "replayed");
    }

    @Test
    void aKeyReusedForAnotherBodyOrQueryIsRefusedAndTheHandlerDoesNotRun() {
        Owner owner = owner("call-reused");
        exchange(WORKFLOWS, HttpMethod.POST, owner.token(), Map.of("name", "Promo"), String.class, "Idempotency-Key",
                "create-42");

        ResponseEntity<ProblemView> otherBody = exchange(WORKFLOWS, HttpMethod.POST, owner.token(),
                Map.of("name", "Other"), ProblemView.class, "Idempotency-Key", "create-42");
        ResponseEntity<ProblemView> otherQuery = exchange(WORKFLOWS + "?kind=video", HttpMethod.POST, owner.token(),
                Map.of("name", "Promo"), ProblemView.class, "Idempotency-Key", "create-42");

        assertThat(otherBody.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(otherBody.getBody().code()).isEqualTo("idempotency-key-reused");
        assertThat(otherQuery.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(workflowsNamed(owner, "Other")).isZero();
    }

    @Test
    void aFailedFirstAnswerFreesTheKey() {
        Owner owner = owner("call-free");

        ResponseEntity<ProblemView> invalid = exchange(WORKFLOWS, HttpMethod.POST, owner.token(), Map.of("name", ""),
                ProblemView.class, "Idempotency-Key", "create-43");
        ResponseEntity<WorkflowView> corrected = createWorkflow(owner, "Corrected", "Idempotency-Key", "create-43");

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT state FROM api_token_idempotency WHERE user_id = ?", String.class,
                owner.id())).isEqualTo("done");
    }

    @Test
    void keysAreScopedToTheirOwner() {
        Owner mai = owner("call-key-mai");
        Owner lan = owner("call-key-lan");

        ResponseEntity<WorkflowView> first = createWorkflow(mai, "Shared", "Idempotency-Key", "create-42");
        ResponseEntity<WorkflowView> second = createWorkflow(lan, "Shared", "Idempotency-Key", "create-42");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(second.getBody().id()).isNotEqualTo(first.getBody().id());
    }

    @Test
    void putAndDeleteIgnoreTheKeyAndNothingIsStored() {
        Owner owner = owner("call-no-key");
        UUID workflow = createWorkflow(owner, "Doomed").getBody().id();

        ResponseEntity<String> first = exchange(WORKFLOWS + "/" + workflow, HttpMethod.DELETE, owner.token(), null,
                String.class, "Idempotency-Key", "delete-1");
        ResponseEntity<String> second = exchange(WORKFLOWS + "/" + workflow, HttpMethod.DELETE, owner.token(), null,
                String.class, "Idempotency-Key", "delete-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_token_idempotency WHERE user_id = ?", Long.class,
                owner.id())).isZero();
    }

    @Test
    void badKeysAndBodiesAreRefusedBeforeTheHandler() {
        Owner owner = owner("call-bad-key");

        ResponseEntity<ProblemView> blank = exchange(WORKFLOWS, HttpMethod.POST, owner.token(),
                Map.of("name", "Blank key"), ProblemView.class, "Idempotency-Key", "   ");
        ResponseEntity<ProblemView> tooLong = exchange(WORKFLOWS, HttpMethod.POST, owner.token(),
                Map.of("name", "Long key"), ProblemView.class, "Idempotency-Key", "k".repeat(81));
        ResponseEntity<ProblemView> huge = exchange(WORKFLOWS, HttpMethod.POST, owner.token(),
                Map.of("name", "Huge body", "padding", "x".repeat(1_500_000)), ProblemView.class,
                "Idempotency-Key", "huge-1");

        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().code()).isEqualTo("validation-failed");
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().code()).isEqualTo("validation-failed");
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(huge.getBody().code()).isEqualTo("idempotency-unsupported");
        assertThat(workflowsNamed(owner, "Blank key") + workflowsNamed(owner, "Long key")
                + workflowsNamed(owner, "Huge body")).isZero();
    }

    @Test
    void aStaleReservationIsTakenOverAndAFreshOneIsInProgress() {
        Owner owner = owner("call-stale");
        createWorkflow(owner, "Stale", "Idempotency-Key", "run-9");

        jdbc.update("UPDATE api_token_idempotency SET state = 'running', http_status = NULL, content_type = NULL,"
                + " response_body = NULL, reserved_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), owner.id());
        ResponseEntity<ProblemView> fresh = exchange(WORKFLOWS, HttpMethod.POST, owner.token(),
                Map.of("name", "Stale"), ProblemView.class, "Idempotency-Key", "run-9");
        jdbc.update("UPDATE api_token_idempotency SET reserved_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(6 * 60)), owner.id());
        ResponseEntity<WorkflowView> stale = createWorkflow(owner, "Stale", "Idempotency-Key", "run-9");

        assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fresh.getBody().code()).isEqualTo("idempotency-in-progress");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(workflowsNamed(owner, "Stale")).isEqualTo(2);
    }

    @Test
    void twoConcurrentKeyedCreatesRunTheHandlerOnce() throws Exception {
        Owner owner = owner("call-race");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> answers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return exchange(WORKFLOWS, HttpMethod.POST, owner.token(), Map.of("name", "Race"),
                            String.class, "Idempotency-Key", "run-10");
                }));
            }
            start.countDown();
            List<ResponseEntity<String>> done = new ArrayList<>();
            for (Future<ResponseEntity<String>> answer : answers) {
                done.add(answer.get(60, TimeUnit.SECONDS));
            }

            assertThat(workflowsNamed(owner, "Race")).isEqualTo(1);
            assertThat(done).filteredOn(answer -> answer.getHeaders().getFirst("Idempotent-Replayed") == null
                    && answer.getStatusCode() == HttpStatus.CREATED).hasSize(1);
            assertThat(done).filteredOn(answer -> answer.getStatusCode() == HttpStatus.CONFLICT
                    || "true".equals(answer.getHeaders().getFirst("Idempotent-Replayed"))).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
