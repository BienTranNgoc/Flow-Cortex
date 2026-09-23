package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code flowcortex.workflow.enabled=false}: new runs are refused cleanly (503, no row
 * written); already-finished runs stay readable. Dedicated {@code @SpringBootTest} context
 * since the property binds once at startup.
 *
 * <p>"Existing run stays readable" is seeded via SQL directly (not {@code startRun}, which this
 * context refuses) — standing in for a run completed before the flag was later flipped off.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.workflow.enabled=false")
@AutoConfigureTestRestTemplate
class WorkflowKillSwitchIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startRunReturns503AndCreatesNoRunRow() {
        UserView user = newUser("killswitch-start");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);

        Long before = countRunsFor(workflow.id());

        ResponseEntity<String> response =
                post("/api/v1/workflows/" + workflow.id() + "/runs", token, new StartRunRequestView("hello"),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(countRunsFor(workflow.id())).isEqualTo(before);
    }

    @Test
    void aRunThatFinishedBeforeTheFlagWasFlippedOffStaysReadable() {
        UserView user = newUser("killswitch-read");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);
        UUID runId = seedCompletedRun(workflow.id(), user.id());

        ResponseEntity<RunResponseView> response = get("/api/v1/runs/" + runId, token, RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("completed");
    }

    private Long countRunsFor(UUID workflowId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflowId);
    }

    private UUID seedCompletedRun(UUID workflowId, UUID ownerId) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 'completed', 'seed-input', ?, ?)",
                runId, workflowId, ownerId, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO run_step (id, run_id, step_order, type, status, input, output, started_at, finished_at) "
                        + "VALUES (?, ?, 1, 'echo', 'completed', 'seed-input', 'seed-input', ?, ?)",
                UUID.randomUUID(), runId, Timestamp.from(now), Timestamp.from(now));
        return runId;
    }
}
