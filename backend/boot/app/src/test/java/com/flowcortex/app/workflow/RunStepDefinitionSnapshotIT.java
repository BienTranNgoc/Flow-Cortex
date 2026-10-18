package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * The run-step definition snapshot migration, re-run against seeded rows: Flyway already applied
 * it at boot, so the test executes the same classpath SQL inside the test transaction, which
 * rolls every seeded and updated row back. Each run step must receive the definition that held
 * its own order when the SQL ran and keep it when the workflow is renumbered afterwards; that the
 * SQL runs before the Product Video renumbering is checked against Flyway's applied history.
 */
@Testcontainers
@SpringBootTest
@Transactional
class RunStepDefinitionSnapshotIT extends AbstractPostgresBootTest {

    private static final String MIGRATION = "db/migration/V2026.10.15.001__run_step_definition_snapshot.sql";

    @Autowired
    private JdbcTemplate jdbc;

    private String backfill;
    private UUID workflowId;
    private UUID speechDefinition;
    private UUID script;
    private UUID speech;
    private UUID withoutDefinition;
    private UUID alreadySnapshotted;

    @BeforeEach
    void seed() throws IOException {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            backfill = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Timestamp now = Timestamp.from(Instant.now());
        workflowId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                + " VALUES (?, ?, ?, false, ?, ?)", workflowId, UUID.randomUUID(), "Snapshot", now, now);
        definition(UUID.randomUUID(), 1, "generate_script", "{\"profile\":\"product_ad_v1\"}", null);
        definition(UUID.randomUUID(), 2, "APPROVAL_CHECKPOINT", null, null);
        speechDefinition = UUID.randomUUID();
        definition(speechDefinition, 3, "generate_speech", "{\"profile\":\"vi_voice_v1\"}", "{step:2}");

        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) VALUES (?, ?, ?, ?, ?)",
                runId, workflowId, UUID.randomUUID(), "running", now);
        script = runStep(runId, 1, "generate_script", "completed");
        runStep(runId, 2, "APPROVAL_CHECKPOINT", "running");
        speech = runStep(runId, 3, "generate_speech", "pending");
        withoutDefinition = runStep(runId, 4, "render_video", "pending");

        UUID laterRunId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) VALUES (?, ?, ?, ?, ?)",
                laterRunId, workflowId, UUID.randomUUID(), "running", now);
        alreadySnapshotted = runStep(laterRunId, 3, "generate_speech", "pending");
        jdbc.update("UPDATE run_step SET config = '{\"profile\":\"kept\"}'::jsonb, input_ref = '{step:9}'"
                + " WHERE id = ?", alreadySnapshotted);
    }

    @Test
    void everyRunStepTakesTheDefinitionHeldByItsOrder() {
        jdbc.execute(backfill);

        assertThat(snapshot(script)).containsEntry("config", "{\"profile\": \"product_ad_v1\"}")
                .containsEntry("input_ref", null);
        assertThat(snapshot(speech)).containsEntry("config", "{\"profile\": \"vi_voice_v1\"}")
                .containsEntry("input_ref", "{step:2}");
        assertThat(snapshot(withoutDefinition)).containsEntry("config", null).containsEntry("input_ref", null);
        assertThat(snapshot(alreadySnapshotted)).containsEntry("config", "{\"profile\": \"kept\"}")
                .containsEntry("input_ref", "{step:9}");
    }

    @Test
    void aSnapshotSurvivesTheWorkflowBeingRenumberedAfterIt() {
        jdbc.execute(backfill);

        jdbc.update("UPDATE workflow_step SET step_order = 4 WHERE id = ?", speechDefinition);
        definition(UUID.randomUUID(), 3, "generate_storyboard", "{\"profile\":\"product_ad_storyboard_v1\"}",
                "{step:2}");

        assertThat(snapshot(speech)).containsEntry("config", "{\"profile\": \"vi_voice_v1\"}")
                .containsEntry("input_ref", "{step:2}");
    }

    @Test
    void theSnapshotIsTakenBeforeAnyMigrationRenumbersProductVideoSteps() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class);

        assertThat(applied).containsSubsequence("2026.10.15.001", "2026.10.16.001", "2026.10.17.001",
                "2026.10.17.002");
    }

    @Test
    void reRunningTheMigrationChangesNothing() {
        jdbc.execute(backfill);
        Map<String, Object> afterFirstRun = snapshot(speech);

        jdbc.update("UPDATE workflow_step SET config = '{\"profile\":\"changed\"}'::jsonb WHERE id = ?",
                speechDefinition);
        jdbc.execute(backfill);

        assertThat(snapshot(speech)).isEqualTo(afterFirstRun);
    }

    private void definition(UUID id, int order, String type, String config, String inputRef) {
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)"
                + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", id, workflowId, order, type, type, config, inputRef);
    }

    private UUID runStep(UUID runId, int order, String type, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, ?, ?, ?)",
                id, runId, order, type, status);
        return id;
    }

    private Map<String, Object> snapshot(UUID runStepId) {
        return jdbc.queryForMap("SELECT config::text AS config, input_ref FROM run_step WHERE id = ?", runStepId);
    }
}
