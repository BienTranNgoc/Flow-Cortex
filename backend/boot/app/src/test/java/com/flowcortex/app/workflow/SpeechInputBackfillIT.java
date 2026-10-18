package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Product Video speech-input backfill migration, re-run against seeded rows: Flyway already
 * applied it at boot, so the test executes the same classpath SQL inside the test transaction,
 * which rolls every seeded and updated row back. The shared database may hold rows from other
 * tests, so assertions target the seeded steps rather than the global update count — except for
 * the re-run, which must change nothing anywhere.
 */
@Testcontainers
@SpringBootTest
@Transactional
class SpeechInputBackfillIT extends AbstractPostgresBootTest {

    private static final String MIGRATION =
            "db/migration/V2026.10.14.004__product_video_speech_input_backfill.sql";
    private static final UUID PRODUCT_VIDEO = UUID.fromString("9aa35334-b672-4b6c-93e8-1a4c2cc14301");
    private static final UUID DOUYIN = UUID.fromString("f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    private String backfill;
    private Seeded preUpgrade;
    private Seeded withoutApprovedRevision;
    private Seeded alreadyBackfilled;
    private Seeded stillRunning;
    private Seeded otherWorkflow;
    private Seeded notJson;

    @BeforeEach
    void seed() throws IOException {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            backfill = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        preUpgrade = seed(PRODUCT_VIDEO, "completed", "{\"script\":\"Kịch bản A\"}", true);
        withoutApprovedRevision = seed(PRODUCT_VIDEO, "completed", "{\"script\":\"Kịch bản B\"}", false);
        alreadyBackfilled = seed(PRODUCT_VIDEO, "completed",
                "{\"script\":\"Kịch bản C\",\"sourceRevisionId\":\"" + UUID.randomUUID() + "\"}", true);
        stillRunning = seed(PRODUCT_VIDEO, "running", "{\"script\":\"Kịch bản D\"}", true);
        otherWorkflow = seed(DOUYIN, "completed", "{\"script\":\"Kịch bản E\"}", true);
        notJson = seed(PRODUCT_VIDEO, "completed", "plain reviewed text", true);
    }

    @Test
    void backfillAddsTheApprovedRevisionToPreUpgradeApprovalsOnly() {
        Map<UUID, String> controlsBefore = outputs(
                withoutApprovedRevision, alreadyBackfilled, stillRunning, otherWorkflow, notJson);

        jdbc.update(backfill);

        JsonNode backfilled = JSON.readTree(output(preUpgrade.stepId()));
        assertThat(backfilled.isObject()).isTrue();
        assertThat(backfilled.propertyNames()).containsExactlyInAnyOrder("script", "sourceRevisionId");
        assertThat(backfilled.get("script").asString()).isEqualTo("Kịch bản A");
        assertThat(backfilled.get("sourceRevisionId").asString())
                .isEqualTo(preUpgrade.approvedRevisionId().toString());

        assertThat(outputs(withoutApprovedRevision, alreadyBackfilled, stillRunning, otherWorkflow, notJson))
                .isEqualTo(controlsBefore);
    }

    @Test
    void backfillIsIdempotent() {
        jdbc.update(backfill);
        Map<UUID, String> afterFirstRun = outputs(
                preUpgrade, withoutApprovedRevision, alreadyBackfilled, stillRunning, otherWorkflow, notJson);

        int changedBySecondRun = jdbc.update(backfill);

        assertThat(changedBySecondRun).isZero();
        assertThat(outputs(preUpgrade, withoutApprovedRevision, alreadyBackfilled, stillRunning, otherWorkflow,
                notJson)).isEqualTo(afterFirstRun);
    }

    /**
     * One run of {@code workflowId} whose step 2 holds {@code output} with an approved review task;
     * the task points at a script revision only when {@code withApprovedRevision}.
     */
    private Seeded seed(UUID workflowId, String stepStatus, String output, boolean withApprovedRevision) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) VALUES (?, ?, ?, ?, ?)",
                runId, workflowId, UUID.randomUUID(), "running", now);
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, output) VALUES (?, ?, ?, ?, ?, ?)",
                stepId, runId, 2, "APPROVAL_CHECKPOINT", stepStatus, output);
        jdbc.update("INSERT INTO review_task (id, run_id, run_step_id, review_type, status) VALUES (?, ?, ?, ?, ?)",
                taskId, runId, stepId, "APPROVAL_CHECKPOINT", "approved");
        if (!withApprovedRevision) {
            return new Seeded(stepId, null);
        }
        UUID revisionId = UUID.randomUUID();
        jdbc.update("INSERT INTO script_revision (id, run_id, review_task_id, revision_number, content, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", revisionId, runId, taskId, 1, "Kịch bản đã duyệt", now);
        jdbc.update("UPDATE review_task SET current_revision_id = ?, approved_revision_id = ? WHERE id = ?",
                revisionId, revisionId, taskId);
        return new Seeded(stepId, revisionId);
    }

    private String output(UUID stepId) {
        return jdbc.queryForObject("SELECT output FROM run_step WHERE id = ?", String.class, stepId);
    }

    private Map<UUID, String> outputs(Seeded... steps) {
        Map<UUID, String> byStep = new LinkedHashMap<>();
        for (Seeded step : List.of(steps)) {
            byStep.put(step.stepId(), output(step.stepId()));
        }
        return byStep;
    }

    private record Seeded(UUID stepId, UUID approvedRevisionId) {}
}
