package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepOutputEdited;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code PATCH|POST|DELETE /api/v1/runs/{runId}/steps/{stepId}/edit} on the real app: runs and steps are seeded
 * with SQL, every call goes through HTTP with a real login.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RunStepEditIT.EditEvents.class)
class RunStepEditIT extends AbstractWorkflowHttpIT {

    private static final String SCRIPT = "{\"profile\":\"p\",\"language\":\"vi\",\"script\":\"Original script\"}";
    private static final String TRANSLATE = "{\"segments\":[{\"index\":0,\"startMs\":0,\"endMs\":1000,"
            + "\"zh\":\"你好\",\"vi\":\"Xin chào\"},{\"index\":1,\"startMs\":1000,\"endMs\":2000,"
            + "\"zh\":\"再见\",\"vi\":\"Tạm biệt\"}]}";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EditEvents events;

    private UserView owner;
    private String token;
    private UUID workflowId;
    private UUID runId;
    private UUID echo;
    private UUID script;
    private UUID translate;

    record FieldView(String pointer, String label, Integer index, String source, String text) {
    }

    record EditView(UUID runId, UUID stepId, String editedOutput, List<FieldView> fields, BigDecimal editDiffPct,
            Instant editedAt, int revision) {
    }

    record ProblemView(int status, String code, Map<String, String> errors, String detail) {
    }

    /** Hears the event the way Drive will: after the publishing transaction committed. */
    static class EditEvents {

        private final List<RunStepOutputEdited> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(RunStepOutputEdited event) {
            received.add(event);
        }

        List<RunStepOutputEdited> received() {
            return received;
        }
    }

    @BeforeEach
    void seed() {
        owner = newUser("step-edit");
        token = loginAs(owner);
        workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        runId = run(owner.id());
        echo = step(runId, 1, "echo", "completed", "Hello world");
        script = step(runId, 2, "generate_script", "completed", SCRIPT);
        translate = step(runId, 3, "translate", "completed", TRANSLATE);
        events.received().clear();
    }

    @Test
    void theFirstEditOfAPlainTextResultKeepsTheOriginalAndAnswersRevisionTwo() {
        ResponseEntity<EditView> response = edit(echo, 1, field("", "Hello Hanoi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        EditView body = response.getBody();
        assertThat(body.runId()).isEqualTo(runId);
        assertThat(body.stepId()).isEqualTo(echo);
        assertThat(body.revision()).isEqualTo(2);
        assertThat(body.editedOutput()).isEqualTo("Hello Hanoi");
        assertThat(body.editDiffPct()).isEqualByComparingTo("45.45");
        assertThat(body.editedAt()).isNotNull();
        assertThat(body.fields()).extracting(FieldView::pointer, FieldView::text)
                .containsExactly(tuple("", "Hello Hanoi"));
        assertThat(column(echo, "output")).isEqualTo("Hello world");
        assertThat(column(echo, "edited_output")).isEqualTo("Hello Hanoi");
        assertThat(versions(echo)).containsExactly("{\"\":\"Hello Hanoi\"}");
    }

    @Test
    void aSecondEditAnswersRevisionThreeAndStoresTwoVersionsHoldingTheFieldMap() {
        edit(echo, 1, field("", "Hello Hanoi"));

        ResponseEntity<EditView> second = edit(echo, 2, field("", "Hello Hue"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().revision()).isEqualTo(3);
        assertThat(versions(echo)).containsExactly("{\"\":\"Hello Hanoi\"}", "{\"\":\"Hello Hue\"}");
    }

    @Test
    void theScriptOfAGenerateScriptStepIsEditedAndTheOriginalOutputStaysUntouched() {
        ResponseEntity<EditView> response = edit(script, 1, field("/script", "Better script"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(column(script, "edited_output"))
                .isEqualTo("{\"profile\":\"p\",\"language\":\"vi\",\"script\":\"Better script\"}");
        assertThat(column(script, "output")).isEqualTo(SCRIPT);
        assertThat(response.getBody().fields()).extracting(FieldView::pointer, FieldView::label)
                .containsExactly(tuple("/script", "script"));
    }

    @Test
    void oneLineOfATranslateStepIsEditedAndTheFieldsNotSentKeepTheirValue() {
        ResponseEntity<EditView> response = edit(translate, 1, field("/segments/1/vi", "Hẹn gặp lại"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(column(translate, "edited_output")).isEqualTo("{\"segments\":[{\"index\":0,\"startMs\":0,"
                + "\"endMs\":1000,\"zh\":\"你好\",\"vi\":\"Xin chào\"},{\"index\":1,\"startMs\":1000,\"endMs\":2000,"
                + "\"zh\":\"再见\",\"vi\":\"Hẹn gặp lại\"}]}");
        assertThat(column(translate, "output")).isEqualTo(TRANSLATE);
        assertThat(response.getBody().fields()).extracting(FieldView::pointer, FieldView::index, FieldView::source,
                FieldView::text).containsExactly(
                tuple("/segments/0/vi", 0, "你好", "Xin chào"),
                tuple("/segments/1/vi", 1, "再见", "Hẹn gặp lại"));
        ResponseEntity<EditView> second = edit(translate, 2, field("/segments/0/vi", "Chào bạn"));
        assertThat(second.getBody().fields()).extracting(FieldView::text).containsExactly("Chào bạn", "Hẹn gặp lại");
    }

    @Test
    void anEmptyTranslateLineIsAccepted() {
        ResponseEntity<EditView> response = edit(translate, 1, field("/segments/0/vi", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().fields().getFirst().text()).isEmpty();
    }

    @Test
    void aBodyThatCannotBeStoredIsRefusedNamingTheFieldAndStoresNothing() {
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "   ")), "expectedRevision", 1)), 400,
                "fields[0].text");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "x".repeat(100_001))), "expectedRevision", 1)),
                400, "fields[0].text");
        assertProblem(patch(script, Map.of("fields", List.of(field("/script", "x".repeat(8_001))),
                "expectedRevision", 1)), 400, "fields[0].text");
        assertProblem(patch(translate, Map.of("fields", List.of(field("/segments/0/vi", "x".repeat(1_001))),
                "expectedRevision", 1)), 400, "fields[0].text");
        assertProblem(patch(echo, Map.of("fields", List.of(), "expectedRevision", 1)), 400, "fields");
        assertProblem(patch(echo, Map.of("expectedRevision", 1)), 400, "fields");
        assertProblem(patch(echo, Map.of("fields", List.of(Map.of("pointer", "")), "expectedRevision", 1)), 400,
                "fields[0].text");
        assertProblem(patch(translate, Map.of("fields", List.of(field("/segments/0/zh", "x")), "expectedRevision", 1)),
                400, "fields[0].pointer");
        assertProblem(patch(translate, Map.of("fields", List.of(field("/segments/9/vi", "x")), "expectedRevision", 1)),
                400, "fields[0].pointer");
        assertProblem(patch(script, Map.of("fields", List.of(field("/profile", "x")), "expectedRevision", 1)), 400,
                "fields[0].pointer");
        assertProblem(patch(translate, Map.of("fields", List.of(field("/segments/0/vi", "a"),
                field("/segments/0/vi", "b")), "expectedRevision", 1)), 400, "fields");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "{\"a\":1}")), "expectedRevision", 1)), 400,
                "fields[0].text");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "x")), "expectedRevision", 0)), 400,
                "expectedRevision");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "x")))), 400, "expectedRevision");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "x")), "expectedRevision", 1.5)), 400,
                "expectedRevision");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "x")), "expectedRevision", "2")), 400,
                "expectedRevision");

        assertThat(column(echo, "edited_output")).isNull();
        assertThat(column(script, "edited_output")).isNull();
        assertThat(column(translate, "edited_output")).isNull();
        assertThat(versionCount(echo) + versionCount(script) + versionCount(translate)).isZero();
        assertThat(events.received()).isEmpty();
    }

    @Test
    void aStepThatIsNotEditableAnswersConflictAndKeepsItsOutput() {
        UUID running = step(runId, 4, "echo", "running", null);
        UUID iterate = step(runId, 5, "iterate", "completed", "[\"a\"]");
        UUID noOutput = step(runId, 6, "echo", "completed", null);
        UUID approval = step(runId, 7, "APPROVAL_CHECKPOINT", "completed", "{\"script\":\"s\"}");
        UUID qaReview = step(runId, 8, "qa.review", "completed", "{\"answers\":\"s\"}");
        UUID storyboard = step(runId, 9, "generate_storyboard", "completed", "{\"scenes\":[]}");
        UUID connector = step(runId, 10, "connector.call", "completed", "{\"body\":\"x\"}");
        UUID structured = step(runId, 11, "echo", "completed", "{\"a\":1}");

        for (UUID id : List.of(running, iterate, noOutput, approval, qaReview, storyboard, connector, structured)) {
            String before = column(id, "output");
            assertProblem(edit(id, 1, field("", "x"), ProblemView.class), 409, null, "step_not_editable");
            assertThat(column(id, "output")).isEqualTo(before);
            assertThat(column(id, "edited_output")).isNull();
        }
        assertThat(events.received()).isEmpty();
    }

    @Test
    void anIterateItemAnotherMembersRunAndAnUnknownIdAnswerNotFound() {
        UUID iterate = step(runId, 4, "iterate", "completed", "[\"a\"]");
        UUID item = jdbcChild(runId, iterate);
        String intruder = loginAs(newUser("step-edit-intruder"));

        assertThat(rawPatch(path(runId, item), token, body(1, field("", "x"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> foreign = rawPatch(path(runId, echo), intruder, body(1, field("", "x")));
        UUID missingRun = UUID.randomUUID();
        ResponseEntity<String> unknown = rawPatch(path(missingRun, echo), token, body(1, field("", "x")));

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutIds(foreign.getBody())).isEqualTo(withoutIds(unknown.getBody()));
        assertThat(column(echo, "edited_output")).isNull();
        assertThat(rawPatch(path(runId, UUID.randomUUID()), token, body(1, field("", "x"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rawPost(path(runId, echo) + "/undo", intruder).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rawDelete(path(runId, echo), intruder).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStaleRevisionAnswersConflictAndStoresNothing() {
        edit(echo, 1, field("", "Hello Hanoi"));

        ResponseEntity<ProblemView> stale = edit(echo, 1, field("", "Other"), ProblemView.class);

        assertProblem(stale, 409, null, "output_revision_conflict");
        assertThat(column(echo, "edited_output")).isEqualTo("Hello Hanoi");
        assertThat(versionCount(echo)).isEqualTo(1);
    }

    @Test
    void aSaveFromATabThatMissedADiscardOrAnUndoToTheOriginalIsRefused() {
        edit(translate, 1, field("/segments/0/vi", "A"), field("/segments/1/vi", "B"));
        assertThat(delete(translate).getBody().revision()).isEqualTo(3);

        assertProblem(edit(translate, 2, field("/segments/0/vi", "C"), ProblemView.class), 409, null,
                "output_revision_conflict");

        edit(echo, 1, field("", "Hello Hanoi"));
        assertThat(post(echo, "/undo").getBody().revision()).isEqualTo(3);

        assertProblem(edit(echo, 2, field("", "Late"), ProblemView.class), 409, null, "output_revision_conflict");
        assertThat(column(echo, "edited_output")).isNull();
    }

    @Test
    void aWholeTextEditThatIsJsonStructureIsRefusedAndAStructuredEchoIsNotEditable() {
        UUID structured = step(runId, 4, "echo", "completed", "{\"a\":1}");

        assertProblem(edit(structured, 1, field("", "{\"a\":1}"), ProblemView.class), 409, null,
                "step_not_editable");
        assertProblem(patch(echo, Map.of("fields", List.of(field("", "{\"a\":1}")), "expectedRevision", 1)), 400,
                "fields[0].text");
    }

    @Test
    void twoTabsSavingTheSameRevisionAtOnceStoreExactlyOne() throws Exception {
        List<Callable<ResponseEntity<String>>> saves = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            String text = "Tab " + i;
            saves.add(() -> {
                start.await();
                return rawPatch(path(runId, echo), token, body(1, field("", text)));
            });
        }
        List<ResponseEntity<String>> answers = runAll(saves, start);

        assertThat(answers).extracting(response -> response.getStatusCode().value()).containsExactlyInAnyOrder(200, 409);
        assertThat(versionCount(echo)).isEqualTo(1);
        assertThat(column(echo, "edited_output")).startsWith("Tab ");
    }

    @Test
    void goingBackToThePreviousEditIsANewRevision() {
        edit(echo, 1, field("", "Hello Hanoi"));
        edit(echo, 2, field("", "Hello Hue"));

        ResponseEntity<EditView> undone = post(echo, "/undo");

        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().revision()).isEqualTo(4);
        assertThat(undone.getBody().editedOutput()).isEqualTo("Hello Hanoi");
        assertThat(column(echo, "edited_output")).isEqualTo("Hello Hanoi");
        assertThat(versionCount(echo)).isEqualTo(3);
    }

    @Test
    void undoingALineEditRestoresThatLineAndKeepsTheOther() {
        edit(translate, 1, field("/segments/1/vi", "Hẹn gặp lại"));
        edit(translate, 2, field("/segments/0/vi", "Chào bạn"));

        ResponseEntity<EditView> undone = post(translate, "/undo");

        assertThat(undone.getBody().fields()).extracting(FieldView::text).containsExactly("Xin chào", "Hẹn gặp lại");
    }

    @Test
    void undoingAndDiscardingAnOutputWithMoreFieldsThanARequestMayHoldSucceed() {
        assertUndoAndDiscardSucceed(2_001, 1);
    }

    @Test
    void undoingAndDiscardingAnOutputWithMoreTextThanARequestMayHoldSucceed() {
        assertUndoAndDiscardSucceed(101, 1_000);
    }

    @Test
    void undoingTheOnlyEditGoesBackToTheOriginal() {
        edit(echo, 1, field("", "Hello Hanoi"));

        ResponseEntity<EditView> undone = post(echo, "/undo");

        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().revision()).isEqualTo(3);
        assertThat(undone.getBody().editedOutput()).isNull();
        assertThat(undone.getBody().editedAt()).isNull();
        assertThat(undone.getBody().editDiffPct()).isNull();
        assertThat(undone.getBody().fields()).extracting(FieldView::text).containsExactly("Hello world");
        assertThat(column(echo, "edited_output")).isNull();
        assertThat(versionCount(echo)).isEqualTo(1);
    }

    @Test
    void thereIsNothingToUndoAfterADiscard() {
        edit(echo, 1, field("", "Hello Hanoi"));
        delete(echo);

        ResponseEntity<ProblemView> undone = restTemplate.exchange(path(runId, echo) + "/undo", HttpMethod.POST,
                bearer(null), ProblemView.class);

        assertProblem(undone, 409, null, "nothing_to_undo");
        assertThat(column(echo, "edited_output")).isNull();
        assertThat(editSeq(echo)).isEqualTo(2);
    }

    @Test
    void discardingKeepsTheHistoryAndRaisesTheRevisionOnlyWhenAnEditWasCurrent() {
        edit(echo, 1, field("", "Hello Hanoi"));
        edit(echo, 2, field("", "Hello Hue"));

        ResponseEntity<EditView> first = delete(echo);
        ResponseEntity<EditView> second = delete(echo);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().revision()).isEqualTo(4);
        assertThat(first.getBody().editedOutput()).isNull();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().revision()).isEqualTo(4);
        assertThat(versionCount(echo)).isEqualTo(2);
        assertThat(column(echo, "edited_output")).isNull();
    }

    @Test
    void theFiftyFirstEditDeletesTheOldestVersionAndTheRevisionKeepsCounting() {
        for (int i = 1; i <= 51; i++) {
            assertThat(edit(echo, i, field("", "Text " + i)).getStatusCode()).as("edit " + i).isEqualTo(HttpStatus.OK);
        }

        assertThat(versionCount(echo)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT min(version) FROM run_step_output_version WHERE run_step_id = ?",
                Integer.class, echo)).isEqualTo(2);
        assertThat(editSeq(echo) + 1).isEqualTo(52);
        assertThat(column(echo, "edited_output")).isEqualTo("Text 51");
    }

    @Test
    void anUndoRacingAnEditLeavesTheStepAndItsHistoryAgreeing() throws Exception {
        edit(echo, 1, field("", "Hello Hanoi"));
        edit(echo, 2, field("", "Hello Hue"));
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ResponseEntity<String>>> calls = List.of(() -> {
            start.await();
            return rawPatch(path(runId, echo), token, body(3, field("", "Racing")));
        }, () -> {
            start.await();
            return rawPost(path(runId, echo) + "/undo", token);
        });

        List<ResponseEntity<String>> answers = runAll(calls, start);

        assertThat(answers).extracting(response -> response.getStatusCode().value()).allMatch(s -> s == 200 || s == 409);
        String current = column(echo, "edited_output");
        assertThat(current).isNotNull();
        assertThat(versions(echo)).contains("{\"\":\"" + current + "\"}");
        assertThat(versionCount(echo)).isLessThanOrEqualTo(4);
    }

    @Test
    void editUndoAndDiscardEachPublishTheEventOnce() {
        edit(echo, 1, field("", "Hello Hanoi"));
        post(echo, "/undo");
        edit(echo, 3, field("", "Hello Hue"));
        delete(echo);

        assertThat(events.received()).hasSize(4).allSatisfy(event -> {
            assertThat(event.runId()).isEqualTo(runId);
            assertThat(event.ownerId()).isEqualTo(owner.id());
        });
    }

    /** An original above the request limits: each stored version holds every line, so undo re-applies them all. */
    private void assertUndoAndDiscardSucceed(int segments, int lineLength) {
        String line = "v".repeat(lineLength);
        String output = IntStream.range(0, segments)
                .mapToObj(i -> "{\"index\":" + i + ",\"startMs\":" + i + ",\"endMs\":" + (i + 1) + ",\"zh\":\"z\",\"vi\":\""
                        + line + "\"}")
                .collect(Collectors.joining(",", "{\"segments\":[", "]}"));
        UUID big = step(runId, 4, "translate", "completed", output);
        edit(big, 1, field("/segments/0/vi", "first"));
        edit(big, 2, field("/segments/1/vi", "second"));

        ResponseEntity<EditView> undone = post(big, "/undo");

        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().fields()).hasSize(segments);
        assertThat(undone.getBody().fields().get(0).text()).isEqualTo("first");
        assertThat(undone.getBody().fields().get(1).text()).isEqualTo(line);
        ResponseEntity<EditView> discarded = delete(big);
        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discarded.getBody().editedOutput()).isNull();
        assertThat(column(big, "edited_output")).isNull();
        assertThat(column(big, "output")).isEqualTo(output);
    }

    private static Map<String, Object> field(String pointer, String text) {
        return Map.of("pointer", pointer, "text", text);
    }

    @SafeVarargs
    private static Map<String, Object> body(int revision, Map<String, Object>... fields) {
        return Map.of("fields", List.of(fields), "expectedRevision", revision);
    }

    private static String path(UUID run, UUID step) {
        return "/api/v1/runs/" + run + "/steps/" + step + "/edit";
    }

    private static String withoutIds(String problem) {
        return problem.replaceAll(UUID_PATTERN, "<id>");
    }

    @SafeVarargs
    private ResponseEntity<EditView> edit(UUID step, int revision, Map<String, Object>... fields) {
        return restTemplate.exchange(path(runId, step), HttpMethod.PATCH, json(body(revision, fields)), EditView.class);
    }

    private <T> ResponseEntity<T> edit(UUID step, int revision, Map<String, Object> field, Class<T> type) {
        return restTemplate.exchange(path(runId, step), HttpMethod.PATCH, json(body(revision, field)), type);
    }

    private ResponseEntity<ProblemView> patch(UUID step, Map<String, Object> body) {
        return restTemplate.exchange(path(runId, step), HttpMethod.PATCH, json(body), ProblemView.class);
    }

    private ResponseEntity<String> rawPatch(String path, String bearer, Object body) {
        return restTemplate.exchange(path, HttpMethod.PATCH, json(bearer, body), String.class);
    }

    private ResponseEntity<String> rawPost(String path, String bearer) {
        return restTemplate.exchange(path, HttpMethod.POST, bearer(bearer), String.class);
    }

    private ResponseEntity<String> rawDelete(String path, String bearer) {
        return restTemplate.exchange(path, HttpMethod.DELETE, bearer(bearer), String.class);
    }

    private ResponseEntity<EditView> post(UUID step, String suffix) {
        return restTemplate.exchange(path(runId, step) + suffix, HttpMethod.POST, bearer(null), EditView.class);
    }

    private ResponseEntity<EditView> delete(UUID step) {
        return restTemplate.exchange(path(runId, step), HttpMethod.DELETE, bearer(null), EditView.class);
    }

    private HttpEntity<Object> json(Object body) {
        return json(token, body);
    }

    private static HttpEntity<Object> json(String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> bearer(String other) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(other == null ? token : other);
        return new HttpEntity<>(headers);
    }

    private static void assertProblem(ResponseEntity<ProblemView> response, int status, String errorField) {
        assertProblem(response, status, errorField, null);
    }

    private static void assertProblem(ResponseEntity<ProblemView> response, int status, String errorField,
            String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        ProblemView problem = response.getBody();
        if (errorField != null) {
            assertThat(problem.errors()).containsKey(errorField);
        }
        if (code != null) {
            assertThat(problem.code()).isEqualTo(code);
        }
    }

    private <T> List<T> runAll(List<Callable<T>> calls, CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<T>> futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID run(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 'completed', 'seed', ?, ?)", id, workflowId, ownerId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return id;
    }

    private UUID step(UUID run, int order, String type, String status, String output) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, output) VALUES (?, ?, ?, ?, ?, ?)",
                id, run, order, type, status, output);
        return id;
    }

    private UUID jdbcChild(UUID run, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, output, parent_step_id, "
                + "parent_attempt, item_index) SELECT ?, run_id, step_order, 'echo', 'completed', 'item', id, 1, 0 "
                + "FROM run_step WHERE id = ?", id, parent);
        return id;
    }

    private String column(UUID step, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM run_step WHERE id = ?", String.class, step);
    }

    private int editSeq(UUID step) {
        return jdbc.queryForObject("SELECT edit_seq FROM run_step WHERE id = ?", Integer.class, step);
    }

    private int versionCount(UUID step) {
        return jdbc.queryForObject("SELECT count(*) FROM run_step_output_version WHERE run_step_id = ?",
                Integer.class, step);
    }

    private List<String> versions(UUID step) {
        return jdbc.queryForList("SELECT content FROM run_step_output_version WHERE run_step_id = ? ORDER BY version",
                String.class, step);
    }
}
