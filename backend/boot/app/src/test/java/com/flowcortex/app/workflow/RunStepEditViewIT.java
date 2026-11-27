package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.StepClosingTestConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What every run view says about a step's edit state, and where an edit is read afterwards: later steps, a run
 * retry, a cancelled run's answer. Runs are seeded with SQL (the engine's own work is not under test), the routes
 * are called over HTTP with a real login. Same settings as the iterate regeneration IT, so both share one context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
@Import(StepClosingTestConfig.class)
class RunStepEditViewIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SCRIPT = "{\"profile\":\"p\",\"language\":\"vi\",\"script\":\"Original script\"}";
    private static final String TRANSLATE = "{\"segments\":[{\"index\":0,\"startMs\":0,\"endMs\":1000,"
            + "\"zh\":\"你好\",\"vi\":\"Xin chào\"},{\"index\":1,\"startMs\":1000,\"endMs\":2000,"
            + "\"zh\":\"谢谢\",\"vi\":\"Cảm ơn\"}]}";
    private static final String CHECKPOINT = "{\"script\":\"Approved text\",\"sourceRevisionId\":\"r1\"}";
    private static final String ITERATE_CONFIG = "{\"child\":{\"type\":\"echo\"}}";

    @Autowired
    private JdbcTemplate jdbc;

    record FieldJson(String pointer, String label, Integer index, String source, String text) {
    }

    record StepJson(UUID id, int stepOrder, String type, String status, String input, String output, boolean editable,
            List<FieldJson> editableFields, String editedOutput, Instant editedAt, BigDecimal editDiffPct,
            int editRevision) {
    }

    record RunJson(UUID id, String status, List<StepJson> steps) {
    }

    record EditJson(int revision, String editedOutput) {
    }

    record ProblemJson(int status, String code) {
    }

    @Test
    void anUneditedStepHasNoEditValuesAtRevisionOneAndOffersItsOriginalText() {
        Member member = member("view-unedited");
        UUID runId = run(member, "completed");
        step(runId, 1, "echo", "completed", "Hello world");

        StepJson step = read(member, runId).steps().getFirst();

        assertThat(step.editedOutput()).isNull();
        assertThat(step.editedAt()).isNull();
        assertThat(step.editDiffPct()).isNull();
        assertThat(step.editRevision()).isEqualTo(1);
        assertThat(step.editable()).isTrue();
        assertThat(step.editableFields()).extracting(FieldJson::pointer, FieldJson::label, FieldJson::text)
                .containsExactly(tuple("", "output", "Hello world"));
    }

    @Test
    void theEditableFlagAndFieldsFollowTheStepTypeAndStatus() {
        Member member = member("view-flags");
        UUID runId = run(member, "completed");
        step(runId, 1, "echo", "completed", "Hello world");
        step(runId, 2, "generate_script", "completed", SCRIPT);
        step(runId, 3, "translate", "completed", TRANSLATE);
        step(runId, 4, "echo", "running", null);
        UUID iterate = step(runId, 5, "iterate", "completed", "{\"results\":[\"a\"]}");
        child(runId, iterate);
        step(runId, 6, "APPROVAL_CHECKPOINT", "completed", CHECKPOINT);
        step(runId, 7, "generate_storyboard", "completed", "{\"scenes\":[]}");
        step(runId, 8, "connector.call", "completed", "{\"status\":200}");

        List<StepJson> steps = read(member, runId).steps();

        assertThat(steps).extracting(StepJson::editable)
                .containsExactly(true, true, true, false, false, false, false, false);
        assertThat(steps.get(0).editableFields()).extracting(FieldJson::pointer, FieldJson::label)
                .containsExactly(tuple("", "output"));
        assertThat(steps.get(1).editableFields()).extracting(FieldJson::pointer, FieldJson::label, FieldJson::text)
                .containsExactly(tuple("/script", "script", "Original script"));
        assertThat(steps.get(2).editableFields())
                .extracting(FieldJson::pointer, FieldJson::label, FieldJson::index, FieldJson::source,
                        FieldJson::text)
                .containsExactly(tuple("/segments/0/vi", "line", 0, "你好", "Xin chào"),
                        tuple("/segments/1/vi", "line", 1, "谢谢", "Cảm ơn"));
        assertThat(steps.subList(3, 8)).allSatisfy(step -> assertThat(step.editableFields()).isEmpty());
    }

    @Test
    void aLineEditShowsInTheFieldsWhileOutputStaysTheOriginal() {
        Member member = member("view-line-edit");
        UUID runId = run(member, "completed");
        UUID translate = step(runId, 1, "translate", "completed", TRANSLATE);

        ResponseEntity<EditJson> edited = edit(member, runId, translate, 1, "/segments/1/vi", "Cám ơn bạn");

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        StepJson step = read(member, runId).steps().getFirst();
        assertThat(step.output()).isEqualTo(TRANSLATE);
        assertThat(step.editedOutput()).contains("Cám ơn bạn");
        assertThat(step.editedAt()).isNotNull();
        assertThat(step.editDiffPct()).isNotNull();
        assertThat(step.editRevision()).isEqualTo(2);
        assertThat(step.editableFields()).extracting(FieldJson::text).containsExactly("Xin chào", "Cám ơn bạn");
    }

    @Test
    void aStepDispatchedAfterTheEditReadsTheEditedText() {
        Member member = member("view-later-step");
        UUID runId = run(member, "error");
        UUID first = step(runId, 1, "echo", "completed", "Hello world");
        UUID second = failedStep(runId, 2);

        assertThat(edit(member, runId, first, 1, "", "Hello Hanoi").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry(member, runId, second).getStatusCode()).isEqualTo(HttpStatus.OK);

        RunView finished = pollUntil(member.user().id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(1).input()).isEqualTo("Hello Hanoi");
        assertThat(finished.steps().get(1).output()).isEqualTo("Hello Hanoi");
        assertThat(finished.steps().get(0).output()).isEqualTo("Hello world");
    }

    @Test
    void anApprovedCheckpointIsNotEditableAndALaterStepStillReadsTheApprovedOutput() {
        Member member = member("view-checkpoint");
        UUID runId = run(member, "error");
        UUID checkpoint = step(runId, 1, "APPROVAL_CHECKPOINT", "completed", CHECKPOINT);
        UUID second = failedStep(runId, 2);

        ResponseEntity<ProblemJson> refused = rawEdit(member, runId, checkpoint, 1, "/script", "Rewritten");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().code()).isEqualTo("step_not_editable");
        assertThat(read(member, runId).steps().getFirst().editable()).isFalse();
        assertThat(retry(member, runId, second).getStatusCode()).isEqualTo(HttpStatus.OK);
        RunView finished = pollUntil(member.user().id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(1).input()).isEqualTo(CHECKPOINT);
    }

    @Test
    void aRunRetryCarriesTheEditAtRevisionTwoWithOneStoredVersion() {
        Member member = member("view-retry-carry");
        RunJson retried = editedThenRetried(member);

        StepJson carried = retried.steps().getFirst();

        assertThat(carried.output()).isEqualTo("Hello world");
        assertThat(carried.editedOutput()).isEqualTo("Hello Hue");
        assertThat(carried.editedAt()).isNotNull();
        assertThat(carried.editDiffPct()).isNotNull();
        assertThat(carried.editRevision()).isEqualTo(2);
        assertThat(carried.editableFields()).extracting(FieldJson::text).containsExactly("Hello Hue");
        assertThat(versions(carried.id())).containsExactly("{\"\":\"Hello Hue\"}");
        assertThat(read(member, retried.id()).steps().getFirst().editRevision()).isEqualTo(2);
    }

    @Test
    void anUndoRightAfterARetryClearsTheCarriedEdit() {
        Member member = member("view-retry-undo-alone");
        RunJson retried = editedThenRetried(member);
        UUID carried = retried.steps().getFirst().id();

        ResponseEntity<EditJson> undone = undo(member, retried.id(), carried);

        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().editedOutput()).isNull();
        StepJson step = read(member, retried.id()).steps().getFirst();
        assertThat(step.editedOutput()).isNull();
        assertThat(step.editableFields()).extracting(FieldJson::text).containsExactly("Hello world");
        assertThat(step.editRevision()).isEqualTo(3);
    }

    @Test
    void anUndoAfterANewEditOfACarriedStepReturnsToTheCarriedEdit() {
        Member member = member("view-retry-undo-back");
        RunJson retried = editedThenRetried(member);
        UUID runId = retried.id();
        UUID carried = retried.steps().getFirst().id();

        assertThat(edit(member, runId, carried, 2, "", "Hello Rome").getBody().revision()).isEqualTo(3);
        ResponseEntity<EditJson> undone = undo(member, runId, carried);

        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().editedOutput()).isEqualTo("Hello Hue");
        assertThat(read(member, runId).steps().getFirst().editableFields()).extracting(FieldJson::text)
                .containsExactly("Hello Hue");
    }

    @Test
    void aCancelAnswerCarriesTheSameRevisionAsAFreshRead() {
        Member member = member("view-cancel");
        UUID runId = run(member, "running");
        UUID first = step(runId, 1, "echo", "completed", "Hello world");
        step(runId, 2, "echo", "pending", null);
        assertThat(edit(member, runId, first, 1, "", "Hello Hanoi").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<RunJson> cancelled = restTemplate.exchange("/api/v1/runs/" + runId + "/cancel",
                HttpMethod.POST, bearer(member), RunJson.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        StepJson answered = cancelled.getBody().steps().getFirst();
        assertThat(answered.editRevision()).isEqualTo(2);
        assertThat(answered.editedOutput()).isEqualTo("Hello Hanoi");
        assertThat(answered).isEqualTo(read(member, runId).steps().getFirst());
    }

    @Test
    void theCreateAnswerCarriesTheEditStateOfEveryStep() {
        Member member = member("view-create");
        WorkflowView workflow = newWorkflow(member.user().id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<RunJson> started = restTemplate.exchange("/api/v1/workflows/" + workflow.id() + "/runs",
                HttpMethod.POST, json(member, new StartRunRequestView("hi")), RunJson.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        StepJson step = started.getBody().steps().getFirst();
        assertThat(step.editable()).isFalse();
        assertThat(step.editRevision()).isEqualTo(1);
        assertThat(step.editedOutput()).isNull();
        assertThat(step.editableFields()).isEmpty();
        assertThat(read(member, started.getBody().id()).steps().getFirst().editRevision()).isEqualTo(1);
    }

    @Test
    void aRequestKeyReplayAnswersTheSameEditStateAsAFreshRead() {
        Member member = member("view-replay");
        WorkflowView workflow = newWorkflow(member.user().id(), false, new NewWorkflowStep("only", "echo", null));
        String key = "card-" + UUID.randomUUID();
        RunView first = workflowRunService.startRun(member.user().id(), workflow.id(), "hi", null, null, null, false,
                key);
        pollUntil(member.user().id(), first.id(), run -> "completed".equals(run.status()), TIMEOUT);

        RunView replay = workflowRunService.startRun(member.user().id(), workflow.id(), "hi", null, null, null,
                false, key);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.steps().getFirst().editable()).isTrue();
        assertThat(replay.steps().getFirst().editableFields()).extracting(field -> field.text())
                .containsExactly("hi");
        assertThat(replay.steps().getFirst().editRevision()).isEqualTo(1);
        assertThat(replay.steps()).isEqualTo(workflowRunService.getRun(member.user().id(), first.id()).steps());
    }

    @Test
    void anIterateRegenerationAnswerCarriesTheSameEditStateAsAFreshRead() {
        Member member = member("view-regenerate");
        WorkflowView workflow = newWorkflow(member.user().id(), false,
                new NewWorkflowStep("each", "iterate", null, ITERATE_CONFIG),
                new NewWorkflowStep("after", "echo", null));
        RunView run = pollUntil(member.user().id(),
                workflowRunService.startRun(member.user().id(), workflow.id(), "[\"a\",\"b\"]").id(),
                view -> !"running".equals(view.status()), TIMEOUT);
        UUID iterate = run.steps().get(0).id();
        UUID echo = run.steps().get(1).id();
        jdbc.update("UPDATE run_step SET output = 'Plain text' WHERE id = ?", echo);
        assertThat(edit(member, run.id(), echo, 1, "", "Corrected").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<RunJson> regenerated = restTemplate.exchange(
                "/api/v1/runs/" + run.id() + "/steps/" + iterate + "/children/0/regenerate", HttpMethod.POST,
                bearer(member), RunJson.class);

        assertThat(regenerated.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<StepJson> answered = regenerated.getBody().steps();
        assertThat(answered).extracting(StepJson::editable, StepJson::editRevision)
                .containsExactly(tuple(false, 1), tuple(true, 2));
        assertThat(answered.get(1).editedOutput()).isEqualTo("Corrected");
        assertThat(answered).extracting(StepJson::editable, StepJson::editRevision, StepJson::editedOutput)
                .isEqualTo(read(member, run.id()).steps().stream()
                        .map(step -> tuple(step.editable(), step.editRevision(), step.editedOutput())).toList());
    }

    private record Member(UserView user, String token) {
    }

    /** A failed run whose step 1 is edited twice (revision 3), then retried as a whole. */
    private RunJson editedThenRetried(Member member) {
        UUID runId = run(member, "error");
        UUID first = step(runId, 1, "echo", "completed", "Hello world");
        failedStep(runId, 2);
        assertThat(edit(member, runId, first, 1, "", "Hello Hanoi").getBody().revision()).isEqualTo(2);
        assertThat(edit(member, runId, first, 2, "", "Hello Hue").getBody().revision()).isEqualTo(3);
        ResponseEntity<RunJson> retried = restTemplate.exchange("/api/v1/runs/" + runId + "/retry", HttpMethod.POST,
                bearer(member), RunJson.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().id()).isNotEqualTo(runId);
        return retried.getBody();
    }

    private Member member(String prefix) {
        UserView user = newUser(prefix);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, user.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = 1000 WHERE user_id = ?", user.id());
        return new Member(user, loginAs(user));
    }

    private RunJson read(Member member, UUID runId) {
        return get("/api/v1/runs/" + runId, member.token(), RunJson.class).getBody();
    }

    private ResponseEntity<EditJson> edit(Member member, UUID runId, UUID stepId, int revision, String pointer,
            String text) {
        return restTemplate.exchange(editPath(runId, stepId), HttpMethod.PATCH,
                json(member, body(revision, pointer, text)), EditJson.class);
    }

    private ResponseEntity<ProblemJson> rawEdit(Member member, UUID runId, UUID stepId, int revision, String pointer,
            String text) {
        return restTemplate.exchange(editPath(runId, stepId), HttpMethod.PATCH,
                json(member, body(revision, pointer, text)), ProblemJson.class);
    }

    private ResponseEntity<EditJson> undo(Member member, UUID runId, UUID stepId) {
        return restTemplate.exchange(editPath(runId, stepId) + "/undo", HttpMethod.POST, bearer(member),
                EditJson.class);
    }

    private ResponseEntity<RunJson> retry(Member member, UUID runId, UUID stepId) {
        return restTemplate.exchange("/api/v1/runs/" + runId + "/steps/" + stepId + "/retry", HttpMethod.POST,
                bearer(member), RunJson.class);
    }

    private static Map<String, Object> body(int revision, String pointer, String text) {
        return Map.of("fields", List.of(Map.of("pointer", pointer, "text", text)), "expectedRevision", revision);
    }

    private static String editPath(UUID runId, UUID stepId) {
        return "/api/v1/runs/" + runId + "/steps/" + stepId + "/edit";
    }

    private static HttpEntity<Object> json(Member member, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<Void> bearer(Member member) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        return new HttpEntity<>(headers);
    }

    private UUID run(Member member, String status) {
        WorkflowView workflow = newWorkflow(member.user().id(), false, new NewWorkflowStep("only", "echo", null));
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        boolean finished = !"running".equals(status);
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, error_message, started_at, "
                + "finished_at) VALUES (?, ?, ?, ?, 'seed', ?, ?, ?)", id, workflow.id(), member.user().id(), status,
                "error".equals(status) ? "failed" : null, now, finished ? now : null);
        return id;
    }

    private UUID step(UUID runId, int order, String type, String status, String output) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output) "
                + "VALUES (?, ?, ?, ?, ?, 'seed', ?)", id, runId, order, type, status, output);
        return id;
    }

    private UUID failedStep(UUID runId, int order) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, error_code, error_message, "
                + "started_at, finished_at) VALUES (?, ?, ?, 'echo', 'error', 'lease_expired', 'failed', ?, ?)", id,
                runId, order, now, now);
        return id;
    }

    private void child(UUID runId, UUID parent) {
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, output, parent_step_id, "
                + "parent_attempt, item_index) SELECT ?, run_id, step_order, 'echo', 'completed', 'a', id, 1, 0 "
                + "FROM run_step WHERE id = ?", UUID.randomUUID(), parent);
    }

    private List<String> versions(UUID stepId) {
        return jdbc.queryForList("SELECT content FROM run_step_output_version WHERE run_step_id = ? ORDER BY version",
                String.class, stepId);
    }
}
