package com.flowcortex.app.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepOutputEdited;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drive follows a step edit end to end with spaces on: the edit, undo and discard routes go over HTTP with a real
 * login, the Memory row is re-projected through the publication registry.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class DriveMemoryEditIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactions;

    record TextView(String text) {
    }

    @Test
    void anEditUndoAndDiscardAreFollowedByTheMemoryEntry() {
        UserView owner = newUser("memory-edit");
        String token = loginAs(owner);
        awaitSpace(owner.id());
        UUID runId = completedEchoRun(owner, "original text");
        awaitEntries(runId, 1);
        UUID entry = jdbc.queryForObject("SELECT id FROM memory_result WHERE run_id = ?", UUID.class, runId);
        UUID step = jdbc.queryForObject("SELECT id FROM run_step WHERE run_id = ?", UUID.class, runId);

        assertThat(patch(token, runId, step, 1, "Edited by the member, with ü").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        await(() -> "Edited by the member, with ü".equals(preview(runId)));
        assertThat(sizeBytes(runId)).isEqualTo("Edited by the member, with ü".getBytes(StandardCharsets.UTF_8).length);
        assertThat(text(token, entry)).isEqualTo("Edited by the member, with ü");

        assertThat(send(HttpMethod.POST, token, runId, step, "/undo").getStatusCode()).isEqualTo(HttpStatus.OK);

        await(() -> "original text".equals(preview(runId)));
        assertThat(sizeBytes(runId)).isEqualTo("original text".length());
        assertThat(text(token, entry)).isEqualTo("original text");

        assertThat(patch(token, runId, step, 3, "Edited again").getStatusCode()).isEqualTo(HttpStatus.OK);
        await(() -> "Edited again".equals(preview(runId)));

        assertThat(send(HttpMethod.DELETE, token, runId, step, "").getStatusCode()).isEqualTo(HttpStatus.OK);

        await(() -> "original text".equals(preview(runId)));
        assertThat(text(token, entry)).isEqualTo("original text");
    }

    @Test
    void anEditOnARunThatIsStillRunningWritesNoMemoryRow() {
        UserView owner = newUser("memory-edit-running");
        String token = loginAs(owner);
        awaitSpace(owner.id());
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID runId = UUID.randomUUID();
        UUID step = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, 'running', 'seed', ?)", runId, workflow.id(), owner.id(),
                Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, output) "
                + "VALUES (?, ?, 1, 'echo', 'completed', 'half way')", step, runId);

        assertThat(patch(token, runId, step, 1, "corrected half way").getStatusCode()).isEqualTo(HttpStatus.OK);

        await(() -> outstandingPublications(runId) == 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class, runId))
                .isZero();
    }

    @Test
    void anEditAndAnUndoHeardTogetherLeaveTheEntryAtTheStepsEffectiveOutput() throws Exception {
        UserView owner = newUser("memory-edit-race");
        String token = loginAs(owner);
        awaitSpace(owner.id());
        UUID runId = completedEchoRun(owner, "base text");
        awaitEntries(runId, 1);
        UUID step = jdbc.queryForObject("SELECT id FROM run_step WHERE run_id = ?", UUID.class, runId);
        assertThat(patch(token, runId, step, 1, "first edit").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, token, runId, step, "/undo").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch(token, runId, step, 3, "final edit").getStatusCode()).isEqualTo(HttpStatus.OK);
        await(() -> "final edit".equals(preview(runId)));
        await(() -> outstandingPublications(runId) == 0);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> deliveries = List.of(pool.submit(() -> deliver(start, runId, owner.id())),
                    pool.submit(() -> deliver(start, runId, owner.id())));
            start.countDown();
            for (Future<?> delivery : deliveries) {
                delivery.get();
            }
        } finally {
            pool.shutdownNow();
        }

        await(() -> outstandingPublications(runId) == 0);
        assertThat(preview(runId)).isEqualTo(jdbc.queryForObject(
                "SELECT COALESCE(edited_output, output) FROM run_step WHERE id = ?", String.class, step))
                .isEqualTo("final edit");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class, runId))
                .isEqualTo(1L);
    }

    private void deliver(CountDownLatch start, UUID runId, UUID ownerId) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to publish", e);
        }
        transactions.executeWithoutResult(status -> publisher.publishEvent(new RunStepOutputEdited(runId, ownerId)));
    }

    private ResponseEntity<String> patch(String token, UUID runId, UUID step, int revision, String text) {
        Map<String, Object> body = Map.of("fields", List.of(Map.of("pointer", "", "text", text)),
                "expectedRevision", revision);
        return restTemplate.exchange(path(runId, step), HttpMethod.PATCH, json(token, body), String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String token, UUID runId, UUID step, String suffix) {
        return restTemplate.exchange(path(runId, step) + suffix, method, json(token, null), String.class);
    }

    private String text(String token, UUID entry) {
        return get("/api/v1/drive/memory/" + entry + "/text", token, TextView.class).getBody().text();
    }

    private static String path(UUID runId, UUID step) {
        return "/api/v1/runs/" + runId + "/steps/" + step + "/edit";
    }

    private static HttpEntity<Object> json(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String preview(UUID runId) {
        List<String> rows = jdbc.queryForList("SELECT preview_text FROM memory_result WHERE run_id = ?", String.class,
                runId);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private long sizeBytes(UUID runId) {
        return jdbc.queryForObject("SELECT size_bytes FROM memory_result WHERE run_id = ?", Long.class, runId);
    }

    private long outstandingPublications(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL "
                + "AND event_type LIKE '%RunStepOutputEdited%' AND serialized_event LIKE ?", Long.class,
                "%" + runId + "%");
    }

    private UUID completedEchoRun(UserView owner, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        RunView run = workflowRunService.startRun(owner.id(), workflow.id(), input);
        awaitTerminal(owner.id(), run.id());
        return run.id();
    }

    private void awaitSpace(UUID ownerId) {
        await(() -> !jdbc.queryForList("SELECT id FROM space WHERE owner_id = ? AND is_default", UUID.class, ownerId)
                .isEmpty());
    }

    private void awaitEntries(UUID runId, long count) {
        await(() -> jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class, runId)
                == count);
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition within %s", TIMEOUT).isBefore(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}
