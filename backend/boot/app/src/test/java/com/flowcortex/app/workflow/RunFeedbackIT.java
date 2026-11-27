package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code PUT /api/v1/runs/{runId}/feedback} on the real app: runs are seeded with SQL, every call goes through HTTP
 * with a real login, and what is stored is read back with SQL.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class RunFeedbackIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    private UserView owner;
    private String token;
    private UUID runId;

    record FeedbackJson(String verdict, String issue, String detail, Instant updatedAt) {
    }

    record RunJson(UUID id, FeedbackJson feedback) {
    }

    record ProblemJson(int status, Map<String, String> errors) {
    }

    @BeforeEach
    void seed() {
        owner = newUser("run-feedback");
        token = loginAs(owner);
        runId = run(owner.id());
    }

    @Test
    void aThumbsDownIsStoredAnswersItBackAndShowsOnTheRunView() {
        assertThat(read(runId, token).feedback()).isNull();

        ResponseEntity<FeedbackJson> response = put(runId, token, body("down", "low_quality", "Too short"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        FeedbackJson answer = response.getBody();
        assertThat(answer.verdict()).isEqualTo("down");
        assertThat(answer.issue()).isEqualTo("low_quality");
        assertThat(answer.detail()).isEqualTo("Too short");
        assertThat(answer.updatedAt()).isNotNull();
        FeedbackJson onRun = read(runId, token).feedback();
        assertThat(onRun.verdict()).isEqualTo("down");
        assertThat(onRun.issue()).isEqualTo("low_quality");
        assertThat(onRun.detail()).isEqualTo("Too short");
        assertThat(onRun.updatedAt()).isNotNull();
        assertThat(stored("feedback_verdict")).isEqualTo("down");
        assertThat(stored("feedback_issue")).isEqualTo("low_quality");
    }

    @Test
    void changingYourMindToThumbsUpClearsTheIssueAndTheDetail() {
        put(runId, token, body("down", "low_quality", "Too short"));

        ResponseEntity<FeedbackJson> response = put(runId, token, body("up", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().verdict()).isEqualTo("up");
        assertThat(response.getBody().issue()).isNull();
        assertThat(response.getBody().detail()).isNull();
        FeedbackJson onRun = read(runId, token).feedback();
        assertThat(onRun.verdict()).isEqualTo("up");
        assertThat(onRun.issue()).isNull();
        assertThat(onRun.detail()).isNull();
        assertThat(stored("feedback_issue")).isNull();
        assertThat(stored("feedback_detail")).isNull();
    }

    @Test
    void malformedFeedbackIsRefusedNamingTheFieldAndStoresNothing() {
        assertRefused(body("down", null, null), "issue");
        assertRefused(body("down", "nonsense", null), "issue");
        assertRefused(body("up", "other", null), "issue");
        assertRefused(body("meh", null, null), "verdict");
        assertRefused(new HashMap<>(), "verdict");
        assertRefused(body("down", "other", "x".repeat(1_001)), "detail");
        assertRefused(body("up", null, "bell\u0007"), "detail");

        assertThat(stored("feedback_verdict")).isNull();
        assertThat(stored("feedback_at")).isNull();
    }

    @Test
    void aDetailOfExactlyTheLimitWithLineBreaksAndTabsIsStored() {
        String detail = "a\nb\r\nc\td" + "x".repeat(1_000 - 8);

        ResponseEntity<FeedbackJson> response = put(runId, token, body("down", "other", detail));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored("feedback_detail")).isEqualTo(detail);
    }

    @Test
    void aBlankDetailIsStoredAsNull() {
        ResponseEntity<FeedbackJson> response = put(runId, token, body("down", "other", "   "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().detail()).isNull();
        assertThat(stored("feedback_detail")).isNull();
        assertThat(stored("feedback_verdict")).isEqualTo("down");
    }

    @Test
    void anotherMembersRunAndAnUnknownRunAreNotFoundAndStoreNothing() {
        String intruder = loginAs(newUser("run-feedback-intruder"));

        ResponseEntity<String> foreign = putRaw(runId, intruder, body("up", null, null));
        ResponseEntity<String> unknown = putRaw(UUID.randomUUID(), token, body("up", null, null));

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stored("feedback_verdict")).isNull();
    }

    @Test
    void aSoftDeletedRunIsNotFound() {
        assertThat(put(runId, token, body("up", null, null)).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ?", runId);

        assertThat(putRaw(runId, token, body("down", "other", null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stored("feedback_verdict")).isEqualTo("up");
    }

    @Test
    void theDatabaseRefusesWhatTheRulesRefuse() {
        assertRefusedByDatabase("feedback_verdict = 'up', feedback_issue = 'other', feedback_at = now()");
        assertRefusedByDatabase("feedback_verdict = 'down', feedback_at = now()");
        assertRefusedByDatabase("feedback_verdict = 'meh', feedback_at = now()");
        assertRefusedByDatabase("feedback_verdict = 'down', feedback_issue = 'bad', feedback_at = now()");
        assertRefusedByDatabase("feedback_verdict = 'up', feedback_at = now(), feedback_detail = repeat('x', 1001)");
        assertRefusedByDatabase("feedback_issue = 'other'");
        assertThat(stored("feedback_verdict")).isNull();
    }

    @Test
    void theLogLineHoldsHasDetailAndNeverTheDetail(CapturedOutput output) {
        put(runId, token, body("down", "technical_error", "My phone is 0901234567"));

        List<String> lines = output.getAll().lines().filter(line -> line.contains("Run feedback recorded")).toList();

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains(runId.toString(), "verdict=down", "issue=technical_error",
                "hasDetail=true");
        assertThat(output.getAll()).doesNotContain("0901234567");
    }

    private void assertRefused(Map<String, Object> body, String field) {
        ResponseEntity<ProblemJson> response = restTemplate.exchange(path(runId), HttpMethod.PUT, json(token, body),
                ProblemJson.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsOnlyKeys(field);
    }

    private void assertRefusedByDatabase(String assignments) {
        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_run SET " + assignments + " WHERE id = ?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Map<String, Object> body(String verdict, String issue, String detail) {
        Map<String, Object> body = new HashMap<>();
        body.put("verdict", verdict);
        if (issue != null) {
            body.put("issue", issue);
        }
        if (detail != null) {
            body.put("detail", detail);
        }
        return body;
    }

    private static String path(UUID run) {
        return "/api/v1/runs/" + run + "/feedback";
    }

    private ResponseEntity<FeedbackJson> put(UUID run, String bearer, Map<String, Object> body) {
        return restTemplate.exchange(path(run), HttpMethod.PUT, json(bearer, body), FeedbackJson.class);
    }

    private ResponseEntity<String> putRaw(UUID run, String bearer, Map<String, Object> body) {
        return restTemplate.exchange(path(run), HttpMethod.PUT, json(bearer, body), String.class);
    }

    private RunJson read(UUID run, String bearer) {
        return get("/api/v1/runs/" + run, bearer, RunJson.class).getBody();
    }

    private static HttpEntity<Object> json(String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String stored(String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM workflow_run WHERE id = ?", String.class, runId);
    }

    private UUID run(UUID ownerId) {
        UUID workflowId = newWorkflow(ownerId, false, new NewWorkflowStep("only", "echo", null)).id();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, 'completed', 'seed', ?, ?)", id, workflowId, ownerId, now, now);
        return id;
    }
}
