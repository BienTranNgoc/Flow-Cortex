package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The batch endpoints on the real app. Dispatch is switched off so the test sets row and batch states itself and
 * nothing starts a run behind it; every test owns fresh users, so owner-scoped reads see only its own batches.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.workflow.batch.dispatch-enabled=false")
@AutoConfigureTestRestTemplate
class BatchRunsEndpointsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    record PageOf<T>(List<T> items, int page, int size, long total) {
    }

    record BatchView(
            UUID id, UUID workflowId, String workflowName, String name, String status, int concurrency,
            int totalRows, long pendingRows, long runningRows, long completedRows, long failedRows,
            long cancelledRows, String pauseReason, String cancelReason, UUID parentBatchId, Instant createdAt,
            Instant finishedAt) {
    }

    record RowView(
            int rowIndex, Integer sourceRowIndex, String input, String status, UUID runId, String errorCode,
            String errorMessage, Instant startedAt, Instant finishedAt) {
    }

    record ProblemView(int status, String detail, String code, Map<String, String> errors) {
    }

    private static final ParameterizedTypeReference<PageOf<BatchView>> BATCH_PAGE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<PageOf<RowView>> ROW_PAGE = new ParameterizedTypeReference<>() {
    };

    @Test
    void createFromRowsAnswers201WithPendingRows() {
        UserView m = newUser("batch-rows");
        WorkflowView w = echoWorkflow(m);
        String token = loginAs(m);

        ResponseEntity<BatchView> created = post("/api/v1/workflows/" + w.id() + "/batches", token,
                Map.of("rows", List.of("red shoes", " ", "blue hat"), "concurrency", 2), BatchView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BatchView batch = created.getBody();
        assertThat(batch.status()).isEqualTo("running");
        assertThat(batch.workflowId()).isEqualTo(w.id());
        assertThat(batch.workflowName()).isEqualTo(w.name());
        assertThat(batch.totalRows()).isEqualTo(2);
        assertThat(batch.pendingRows()).isEqualTo(2);
        assertThat(batch.concurrency()).isEqualTo(2);
        assertThat(batch.parentBatchId()).isNull();
        assertThat(rows(batch.id(), token, "")).extracting(RowView::rowIndex, RowView::input, RowView::status)
                .containsExactly(tuple(0, "red shoes", "pending"),
                        tuple(1, "blue hat", "pending"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class, w.id()))
                .as("creating a batch starts no run").isZero();
    }

    @Test
    void createFromCsvContentSkipsTheHeader() {
        UserView m = newUser("batch-csv");
        String token = loginAs(m);

        ResponseEntity<BatchView> created = post("/api/v1/workflows/" + echoWorkflow(m).id() + "/batches", token,
                Map.of("content", "prompt\r\nred shoes\r\n\r\nblue hat\r\n", "format", "csv", "skipFirstLine", true),
                BatchView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().concurrency()).as("concurrency defaults to one").isEqualTo(1);
        assertThat(rows(created.getBody().id(), token, "")).extracting(RowView::input)
                .containsExactly("red shoes", "blue hat");
    }

    @Test
    void mediaFirstWorkflowIs422TextInputOnly() {
        UserView m = newUser("batch-media");
        WorkflowView w = newWorkflow(m.id(), false, new NewWorkflowStep("Inspect the file", "probe", null));

        ResponseEntity<ProblemView> refused = post("/api/v1/workflows/" + w.id() + "/batches", loginAs(m),
                Map.of("rows", List.of("a")), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().code()).isEqualTo("workflow.batch.textInputOnly");
        assertThat(refused.getBody().detail()).contains("starts from an upload");
        assertThat(batchesOf(m)).isZero();
    }

    @Test
    void incompleteWorkflowIsTheRunStart422() {
        UserView m = newUser("batch-incomplete");
        WorkflowView dangling = newWorkflow(m.id(), false, new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("dangling", "echo", "{step:9.field}"));

        ResponseEntity<ProblemView> refused = post("/api/v1/workflows/" + dangling.id() + "/batches", loginAs(m),
                Map.of("rows", List.of("a")), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().code()).isEqualTo("WORKFLOW_INCOMPLETE");
        assertThat(batchesOf(m)).isZero();
    }

    @Test
    void invisibleWorkflowIs404() {
        UserView owner = newUser("batch-private-owner");
        UserView m = newUser("batch-private-caller");
        WorkflowView privateWorkflow = echoWorkflow(owner);

        ResponseEntity<ProblemView> refused = post("/api/v1/workflows/" + privateWorkflow.id() + "/batches",
                loginAs(m), Map.of("rows", List.of("a")), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(batchesOf(m)).isZero();
    }

    @Test
    void sixthUnfinishedBatchIs409() {
        UserView m = newUser("batch-sixth");
        WorkflowView w = echoWorkflow(m);
        String token = loginAs(m);
        List<BatchView> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createBatch(w, token, List.of("row")));
        }
        assertThat(post("/api/v1/batches/" + created.get(0).id() + "/pause", token, null, BatchView.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ProblemView> sixth = post("/api/v1/workflows/" + w.id() + "/batches", token,
                Map.of("rows", List.of("row")), ProblemView.class);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sixth.getBody().detail()).contains("5 unfinished batches");
        assertThat(batchesOf(m)).isEqualTo(5);

        assertThat(post("/api/v1/batches/" + created.get(1).id() + "/cancel", token, null, BatchView.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/workflows/" + w.id() + "/batches", token, Map.of("rows", List.of("row")),
                BatchView.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listIsNewestFirstAndOwnOnly() {
        UserView m = newUser("batch-list");
        UserView n = newUser("batch-list-other");
        String token = loginAs(m);
        BatchView first = createBatch(echoWorkflow(m), token, List.of("a"));
        BatchView second = createBatch(echoWorkflow(m), token, List.of("b"));
        createBatch(echoWorkflow(n), loginAs(n), List.of("c"));

        PageOf<BatchView> page = batchPage("/api/v1/batches", token);

        assertThat(page.items()).extracting(BatchView::id).containsExactly(second.id(), first.id());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().getFirst().workflowName()).isEqualTo(second.workflowName());
        assertThat(batchPage("/api/v1/batches?size=1&page=1", token).items()).extracting(BatchView::id)
                .containsExactly(first.id());
        for (String query : List.of("size=101", "size=0", "page=-1")) {
            ResponseEntity<ProblemView> response = get("/api/v1/batches?" + query, token, ProblemView.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().errors()).as(query).containsKey(query.substring(0, query.indexOf('=')));
        }
        assertThat(get("/api/v1/batches", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detailCountsEachRowStatus() {
        UserView m = newUser("batch-detail");
        String token = loginAs(m);
        BatchView batch = createBatch(echoWorkflow(m), token, List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));
        for (int index = 0; index <= 2; index++) {
            setRow(batch.id(), index, "completed");
        }
        setRow(batch.id(), 3, "failed");
        setRow(batch.id(), 4, "running");
        setRow(batch.id(), 5, "running");
        setRow(batch.id(), 6, "starting");

        BatchView detail = get("/api/v1/batches/" + batch.id(), token, BatchView.class).getBody();

        assertThat(detail.status()).isEqualTo("running");
        assertThat(detail.totalRows()).isEqualTo(10);
        assertThat(detail.pendingRows()).as("a starting row is still pending").isEqualTo(4);
        assertThat(detail.runningRows()).isEqualTo(2);
        assertThat(detail.completedRows()).isEqualTo(3);
        assertThat(detail.failedRows()).isEqualTo(1);
        assertThat(detail.cancelledRows()).isZero();
        assertThat(rows(batch.id(), token, "?status=pending")).extracting(RowView::rowIndex)
                .containsExactly(6, 7, 8, 9);
    }

    @Test
    void pauseResumeAndTheir409s() {
        UserView m = newUser("batch-pause");
        String token = loginAs(m);
        BatchView batch = createBatch(echoWorkflow(m), token, List.of("a", "b"));
        String base = "/api/v1/batches/" + batch.id();

        ResponseEntity<ProblemView> resumeRunning = post(base + "/resume", token, null, ProblemView.class);
        assertThat(resumeRunning.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resumeRunning.getBody().detail()).contains("running");

        BatchView paused = post(base + "/pause", token, null, BatchView.class).getBody();
        assertThat(paused.status()).isEqualTo("paused");
        assertThat(paused.pauseReason()).isEqualTo("member");
        ResponseEntity<ProblemView> pauseAgain = post(base + "/pause", token, null, ProblemView.class);
        assertThat(pauseAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pauseAgain.getBody().detail()).contains("paused");

        BatchView resumed = post(base + "/resume", token, null, BatchView.class).getBody();
        assertThat(resumed.status()).isEqualTo("running");
        assertThat(resumed.pauseReason()).isNull();

        jdbc.update("UPDATE batch_run SET status = 'completed', finished_at = now() WHERE id = ?", batch.id());
        ResponseEntity<ProblemView> resumeFinished = post(base + "/resume", token, null, ProblemView.class);
        assertThat(resumeFinished.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resumeFinished.getBody().detail()).contains("completed");
        assertThat(post(base + "/pause", token, null, ProblemView.class).getBody().detail()).contains("completed");
    }

    @Test
    void cancelTurnsPendingRowsCancelledAndIsThen409() {
        UserView m = newUser("batch-cancel");
        String token = loginAs(m);
        BatchView batch = createBatch(echoWorkflow(m), token, List.of("a", "b", "c", "d"));
        setRow(batch.id(), 0, "running");
        setRow(batch.id(), 1, "starting");

        ResponseEntity<BatchView> cancelled = post("/api/v1/batches/" + batch.id() + "/cancel", token, null,
                BatchView.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo("cancelled");
        assertThat(cancelled.getBody().cancelReason()).isEqualTo("member");
        assertThat(cancelled.getBody().cancelledRows()).isEqualTo(2);
        assertThat(rowStatuses(batch.id())).containsExactly("running", "starting", "cancelled", "cancelled");
        ResponseEntity<ProblemView> again = post("/api/v1/batches/" + batch.id() + "/cancel", token, null,
                ProblemView.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().detail()).contains("cancelled");
        assertThat(post("/api/v1/batches/" + batch.id() + "/resume", token, null, ProblemView.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancelWithRunCancellationOffAnswers200WithALiveRow() {
        UserView m = newUser("batch-cancel-off");
        String token = loginAs(m);
        BatchView batch = createBatch(echoWorkflow(m), token, List.of("a", "b"));
        setRow(batch.id(), 0, "running");
        post("/api/v1/batches/" + batch.id() + "/pause", token, null, BatchView.class);

        ResponseEntity<BatchView> cancelled = post("/api/v1/batches/" + batch.id() + "/cancel", token, null,
                BatchView.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo("cancelled");
        assertThat(cancelled.getBody().runningRows()).as("the live row is left to finish").isEqualTo(1);
        assertThat(cancelled.getBody().finishedAt()).as("not finished while a row is live").isNull();
    }

    @Test
    void bothInputsOrNeitherIs400AndStoresNothing() {
        UserView m = newUser("batch-inputs");
        String token = loginAs(m);
        String path = "/api/v1/workflows/" + echoWorkflow(m).id() + "/batches";

        ResponseEntity<ProblemView> both = post(path, token, Map.of("rows", List.of("a"), "content", "a"),
                ProblemView.class);
        ResponseEntity<ProblemView> neither = post(path, token, Map.of("name", "x"), ProblemView.class);
        ResponseEntity<ProblemView> tooWide = post(path, token, Map.of("rows", List.of("a"), "concurrency", 11),
                ProblemView.class);
        ResponseEntity<ProblemView> allEmpty = post(path, token, Map.of("rows", List.of(" ", "")), ProblemView.class);

        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(both.getBody().errors()).containsKey("exactlyOneInput");
        assertThat(neither.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(neither.getBody().errors()).containsKey("exactlyOneInput");
        assertThat(tooWide.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooWide.getBody().errors()).containsKey("concurrency");
        assertThat(allEmpty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(allEmpty.getBody().errors()).containsKey("rows");
        assertThat(batchesOf(m)).isZero();
    }

    @Test
    void sizeLimitsAreRefusedNamingTheFieldAndTheRow() {
        UserView m = newUser("batch-limits");
        String token = loginAs(m);
        String path = "/api/v1/workflows/" + echoWorkflow(m).id() + "/batches";
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add("row " + i);
        }
        List<String> padded = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            padded.add(i < 400 ? "row " + i : "");
        }
        List<String> lines = new ArrayList<>(List.of("a", "b", "c", "x".repeat(10_001)));

        ResponseEntity<ProblemView> rows501 = post(path, token, Map.of("rows", tooMany), ProblemView.class);
        ResponseEntity<ProblemView> paddedRows = post(path, token, Map.of("rows", padded), ProblemView.class);
        ResponseEntity<ProblemView> longRow = post(path, token, Map.of("content", String.join("\n", lines)),
                ProblemView.class);
        ResponseEntity<ProblemView> longName = post(path, token, Map.of("rows", List.of("a"), "name", "n".repeat(201)),
                ProblemView.class);

        assertThat(rows501.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rows501.getBody().errors()).containsKey("rows");
        assertThat(paddedRows.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(paddedRows.getBody().errors()).containsKey("rows");
        assertThat(longRow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longRow.getBody().errors()).containsOnlyKeys("content");
        assertThat(longRow.getBody().errors().get("content")).contains("Row 3 ");
        assertThat(longName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longName.getBody().errors()).containsKey("name");
        assertThat(batchesOf(m)).isZero();
    }

    @Test
    void contentInJsonOfFormatJsonThatDoesNotParseIs400ButDetectedBracketsFallBackToLines() {
        UserView m = newUser("batch-json");
        String token = loginAs(m);
        String path = "/api/v1/workflows/" + echoWorkflow(m).id() + "/batches";

        ResponseEntity<ProblemView> broken = post(path, token, Map.of("content", "[\"a\", ", "format", "json"),
                ProblemView.class);
        ResponseEntity<BatchView> bracketedLines = post(path, token, Map.of("content", "[1]\n[2]"), BatchView.class);
        ResponseEntity<BatchView> array = post(path, token, Map.of("content", "[\"a\", {\"k\": 1}, 3]"),
                BatchView.class);

        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(broken.getBody().errors()).containsOnlyKeys("content");
        assertThat(bracketedLines.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(bracketedLines.getBody().id(), token, "")).extracting(RowView::input)
                .containsExactly("[1]", "[2]");
        assertThat(rows(array.getBody().id(), token, "")).extracting(RowView::input)
                .containsExactly("a", "{\"k\":1}", "3");
        assertThat(batchesOf(m)).isEqualTo(2);
    }

    @Test
    void rowsFilteredToFailedInRowOrder() {
        UserView m = newUser("batch-rows-failed");
        WorkflowView w = echoWorkflow(m);
        String token = loginAs(m);
        BatchView batch = createBatch(w, token, List.of("0", "1", "2", "3", "4", "5", "6", "7", "8"));
        UUID run = seedRun(w.id(), m.id(), "error");
        setRow(batch.id(), 7, "failed");
        setRow(batch.id(), 2, "failed");
        jdbc.update("UPDATE batch_run_row SET run_id = ?, error_code = 'runError', error_message = 'boom' "
                + "WHERE batch_id = ? AND row_index = 2", run, batch.id());

        List<RowView> failed = rows(batch.id(), token, "?status=failed");

        assertThat(failed).extracting(RowView::rowIndex).containsExactly(2, 7);
        assertThat(failed.getFirst().runId()).isEqualTo(run);
        assertThat(failed.getFirst().errorCode()).isEqualTo("runError");
        assertThat(failed.getFirst().errorMessage()).isEqualTo("boom");
        assertThat(get("/api/v1/batches/" + batch.id() + "/rows?status=starting", token, ProblemView.class)
                .getBody().errors()).containsKey("status");
        assertThat(get("/api/v1/batches/" + batch.id() + "/rows?status=bogus", token, ProblemView.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        PageOf<RowView> second = restTemplate.exchange("/api/v1/batches/" + batch.id() + "/rows?size=4&page=2",
                HttpMethod.GET, bearer(token), ROW_PAGE).getBody();
        assertThat(second.items()).extracting(RowView::rowIndex).containsExactly(8);
        assertThat(second.total()).isEqualTo(9);
    }

    @Test
    void retryCreatesOneLinkedChildThen409AlreadyRetried() {
        UserView m = newUser("batch-retry");
        String token = loginAs(m);
        BatchView parent = createBatch(echoWorkflow(m), token, "Shoes", List.of("0", "1", "two", "3", "4", "5", "6",
                "seven"));
        settle(parent.id(), "completed_with_errors");
        setRow(parent.id(), 2, "failed");
        setRow(parent.id(), 7, "failed");

        ResponseEntity<BatchView> retried = post("/api/v1/batches/" + parent.id() + "/retry", token, null,
                BatchView.class);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BatchView child = retried.getBody();
        assertThat(child.name()).isEqualTo("Shoes (retry)");
        assertThat(child.parentBatchId()).isEqualTo(parent.id());
        assertThat(child.status()).isEqualTo("running");
        assertThat(child.workflowId()).isEqualTo(parent.workflowId());
        assertThat(child.concurrency()).isEqualTo(parent.concurrency());
        assertThat(rows(child.id(), token, "")).extracting(RowView::rowIndex, RowView::sourceRowIndex, RowView::input)
                .containsExactly(tuple(0, 2, "two"),
                        tuple(1, 7, "seven"));
        ResponseEntity<ProblemView> again = post("/api/v1/batches/" + parent.id() + "/retry", token, null,
                ProblemView.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().detail()).contains(child.id().toString());
        assertThat(batchesOf(m)).isEqualTo(2);
    }

    @Test
    void retryOfARunningBatchIs409AndWithoutFailuresIs422() {
        UserView m = newUser("batch-retry-refused");
        String token = loginAs(m);
        BatchView running = createBatch(echoWorkflow(m), token, List.of("a"));
        BatchView clean = createBatch(echoWorkflow(m), token, List.of("a"));
        settle(clean.id(), "completed");
        setRow(clean.id(), 0, "completed");

        ResponseEntity<ProblemView> notFinished = post("/api/v1/batches/" + running.id() + "/retry", token, null,
                ProblemView.class);
        ResponseEntity<ProblemView> noFailures = post("/api/v1/batches/" + clean.id() + "/retry", token, null,
                ProblemView.class);

        assertThat(notFinished.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notFinished.getBody().detail()).contains("finished");
        assertThat(noFailures.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noFailures.getBody().code()).isEqualTo("workflow.batch.noFailedRows");
        assertThat(batchesOf(m)).isEqualTo(2);
    }

    @Test
    void retryOfACancelledBatchWithARunStillStoppingIs409NotFinished() {
        UserView m = newUser("batch-retry-stopping");
        WorkflowView w = echoWorkflow(m);
        String token = loginAs(m);
        BatchView batch = createBatch(w, token, List.of("a", "b"));
        setRow(batch.id(), 0, "failed");
        setRow(batch.id(), 1, "running");
        post("/api/v1/batches/" + batch.id() + "/cancel", token, null, BatchView.class);

        ResponseEntity<ProblemView> response = post("/api/v1/batches/" + batch.id() + "/retry", token, null,
                ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).contains("finished");
    }

    @Test
    void retrySkipsAFailedRowWhoseRunWasReopened() {
        UserView m = newUser("batch-retry-reopened");
        WorkflowView w = echoWorkflow(m);
        String token = loginAs(m);
        BatchView parent = createBatch(w, token, List.of("0", "1", "two", "3", "4", "5", "6", "seven", "8", "nine"));
        settle(parent.id(), "completed_with_errors");
        UUID completedAgain = seedRun(w.id(), m.id(), "completed");
        UUID runningAgain = seedRun(w.id(), m.id(), "running");
        UUID stillError = seedRun(w.id(), m.id(), "error");
        failRow(parent.id(), 2, null);
        failRow(parent.id(), 7, completedAgain);
        failRow(parent.id(), 8, runningAgain);
        failRow(parent.id(), 9, stillError);

        BatchView child = post("/api/v1/batches/" + parent.id() + "/retry", token, null, BatchView.class).getBody();

        assertThat(rows(child.id(), token, "")).extracting(RowView::sourceRowIndex, RowView::input)
                .containsExactly(tuple(2, "two"),
                        tuple(9, "nine"));
        BatchView settled = get("/api/v1/batches/" + parent.id(), token, BatchView.class).getBody();
        assertThat(settled.completedRows()).as("the re-opened run's row is counted as completed").isEqualTo(1);
        assertThat(settled.failedRows()).isEqualTo(3);
        assertThat(rows(parent.id(), token, "?status=completed")).extracting(RowView::rowIndex).contains(7);
    }

    @Test
    void retryNameOfALongParentFitsTwoHundredCharacters() {
        UserView m = newUser("batch-retry-name");
        String token = loginAs(m);
        String name = "n".repeat(200);
        BatchView parent = createBatch(echoWorkflow(m), token, name, List.of("a"));
        settle(parent.id(), "completed_with_errors");
        setRow(parent.id(), 0, "failed");
        BatchView unnamed = createBatch(echoWorkflow(m), token, List.of("a"));
        settle(unnamed.id(), "completed_with_errors");
        setRow(unnamed.id(), 0, "failed");

        ResponseEntity<BatchView> retried = post("/api/v1/batches/" + parent.id() + "/retry", token, null,
                BatchView.class);
        ResponseEntity<BatchView> retriedUnnamed = post("/api/v1/batches/" + unnamed.id() + "/retry", token, null,
                BatchView.class);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getBody().name()).isEqualTo("n".repeat(192) + " (retry)").hasSize(200);
        assertThat(retriedUnnamed.getBody().name()).isEqualTo("Retry");
    }

    @Test
    void anotherMembersBatchIs404OnEveryRoute() {
        UserView m = newUser("batch-owner");
        UserView n = newUser("batch-intruder");
        BatchView batch = createBatch(echoWorkflow(m), loginAs(m), List.of("a", "b"));
        settle(batch.id(), "completed_with_errors");
        setRow(batch.id(), 0, "failed");
        String token = loginAs(n);
        String base = "/api/v1/batches/" + batch.id();

        for (String path : List.of("", "/rows")) {
            ResponseEntity<ProblemView> response = get(base + path, token, ProblemView.class);
            assertThat(response.getStatusCode()).as("GET " + path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().detail()).as("GET " + path).contains("Batch not found");
        }
        for (String action : List.of("/pause", "/resume", "/cancel", "/retry")) {
            assertThat(post(base + action, token, null, ProblemView.class).getStatusCode()).as("POST " + action)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(get(base + "/rows?status=bogus", token, ProblemView.class).getStatusCode())
                .as("the owner check comes before the status check").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(batchPage("/api/v1/batches", token).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM batch_run WHERE id = ?", String.class, batch.id()))
                .isEqualTo("completed_with_errors");
        assertThat(batchesOfWorkflow(batch.workflowId())).isEqualTo(1);
    }

    private WorkflowView echoWorkflow(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
    }

    private BatchView createBatch(WorkflowView workflow, String token, List<String> rows) {
        return createBatch(workflow, token, null, rows);
    }

    private BatchView createBatch(WorkflowView workflow, String token, String name, List<String> rows) {
        Map<String, Object> body = new HashMap<>();
        body.put("rows", rows);
        if (name != null) {
            body.put("name", name);
        }
        ResponseEntity<BatchView> created = post("/api/v1/workflows/" + workflow.id() + "/batches", token, body,
                BatchView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private List<RowView> rows(UUID batchId, String token, String query) {
        return restTemplate.exchange("/api/v1/batches/" + batchId + "/rows" + query, HttpMethod.GET, bearer(token),
                ROW_PAGE).getBody().items();
    }

    private PageOf<BatchView> batchPage(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, bearer(token), BATCH_PAGE).getBody();
    }

    private List<String> rowStatuses(UUID batchId) {
        return jdbc.queryForList("SELECT status FROM batch_run_row WHERE batch_id = ? ORDER BY row_index",
                String.class, batchId);
    }

    private void setRow(UUID batchId, int rowIndex, String status) {
        jdbc.update("UPDATE batch_run_row SET status = ? WHERE batch_id = ? AND row_index = ?", status, batchId,
                rowIndex);
    }

    private void failRow(UUID batchId, int rowIndex, UUID runId) {
        jdbc.update("UPDATE batch_run_row SET status = 'failed', run_id = ?, error_code = 'runError' "
                + "WHERE batch_id = ? AND row_index = ?", runId, batchId, rowIndex);
    }

    /** Ends the batch as the dispatcher would: its status and a finish time. */
    private void settle(UUID batchId, String status) {
        jdbc.update("UPDATE batch_run SET status = ?, finished_at = now() WHERE id = ?", status, batchId);
    }

    private long batchesOf(UserView owner) {
        return jdbc.queryForObject("SELECT count(*) FROM batch_run WHERE owner_id = ?", Long.class, owner.id());
    }

    private long batchesOfWorkflow(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM batch_run WHERE workflow_id = ?", Long.class, workflowId);
    }

    private UUID seedRun(UUID workflowId, UUID ownerId, String status) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, ?, 'seed', ?)", runId, workflowId, ownerId, status, Timestamp.from(Instant.now()));
        return runId;
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
