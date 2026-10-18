package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The migration that closes Product Video runs started on the earlier, shorter pipeline, re-run
 * against seeded rows inside the test transaction: Flyway already applied it at boot, so only the
 * rows seeded here can match, and the rollback removes them.
 */
@Testcontainers
@SpringBootTest
@Transactional
class OldPipelineRunClosureIT extends AbstractPostgresBootTest {

    private static final String MIGRATION =
            "db/migration/V2026.10.17.004__product_video_close_runs_on_the_old_pipeline.sql";
    private static final UUID PRODUCT_VIDEO = UUID.fromString("9aa35334-b672-4b6c-93e8-1a4c2cc14301");
    private static final UUID DOUYIN = UUID.fromString("f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101");
    private static final String MESSAGE =
            "Product Video moved to a new pipeline while this run was in progress. Start a new run.";

    @Autowired
    private JdbcTemplate jdbc;

    private String closure;

    @BeforeEach
    void readMigration() throws IOException {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            closure = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void closesAnUnfinishedRunOnTheOldPipelineAndOnlyItsRunningStep() {
        UUID run = seedRun(PRODUCT_VIDEO, "waiting_approval", 4);
        jdbc.update("UPDATE run_step SET status = 'completed' WHERE run_id = ? AND step_order = 1", run);
        jdbc.update("UPDATE run_step SET status = 'running' WHERE run_id = ? AND step_order = 2", run);

        jdbc.update(closure);

        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT status, error_message, finished_at FROM workflow_run WHERE id = ?", run);
        assertThat(closed.get("status")).isEqualTo("error");
        assertThat(closed.get("error_message")).isEqualTo(MESSAGE);
        assertThat(closed.get("finished_at")).isNotNull();
        assertThat(stepStatus(run, 1)).isEqualTo("completed");
        Map<String, Object> waiting = jdbc.queryForMap(
                "SELECT status, error_code, error_message FROM run_step WHERE run_id = ? AND step_order = 2", run);
        assertThat(waiting.get("status")).isEqualTo("error");
        assertThat(waiting.get("error_code")).isEqualTo("workflow_changed");
        assertThat(waiting.get("error_message")).isEqualTo(MESSAGE);
        assertThat(stepStatus(run, 3)).isEqualTo("pending");
        assertThat(stepStatus(run, 4)).isEqualTo("pending");
    }

    @Test
    void leavesCurrentPipelineRunsFinishedRunsAndOtherWorkflowsAlone() {
        UUID current = seedRun(PRODUCT_VIDEO, "running", 7);
        UUID finished = seedRun(PRODUCT_VIDEO, "completed", 4);
        UUID otherWorkflow = seedRun(DOUYIN, "running", 3);

        jdbc.update(closure);

        assertThat(runStatus(current)).isEqualTo("running");
        assertThat(runStatus(finished)).isEqualTo("completed");
        assertThat(runStatus(otherWorkflow)).isEqualTo("running");
    }

    @Test
    void reRunningTheMigrationChangesNothing() {
        seedRun(PRODUCT_VIDEO, "running", 4);
        jdbc.update(closure);

        int changed = jdbc.update(closure);

        assertThat(changed).isZero();
    }

    private UUID seedRun(UUID workflowId, String status, int steps) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) VALUES (?, ?, ?, ?, ?)",
                runId, workflowId, UUID.randomUUID(), status, Timestamp.from(Instant.now()));
        for (int order = 1; order <= steps; order++) {
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), runId, order, "echo", "pending");
        }
        return runId;
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, runId);
    }

    private String stepStatus(UUID runId, int order) {
        return jdbc.queryForObject("SELECT status FROM run_step WHERE run_id = ? AND step_order = ?", String.class,
                runId, order);
    }
}
