package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.application.WorkflowSnapshots;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Constraints, seeds and backfill of the workflow version tables, through {@link JdbcTemplate}
 * only. The Testcontainers database is shared with other ITs, so every query is scoped to the
 * rows a test inserted; the backfill tests roll back, and the constraint tests insert unshared
 * workflows because a refused statement cannot run inside a transaction that carries on.
 */
@Testcontainers
@SpringBootTest
class WorkflowVersionSchemaIT extends AbstractWorkflowModuleIT {

    private static final UUID DOUYIN_ID = UUID.fromString("f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101");
    private static final String MIGRATION = "db/migration/V2026.11.10.160__workflow_version.sql";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aPointerToAnotherWorkflowsVersionIsRefused() {
        UUID own = insertWorkflow(false);
        UUID other = insertWorkflow(false);
        UUID otherVersion = insertVersion(other, 1, "publish", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow SET published_version_id = ? WHERE id = ?",
                otherVersion, own)).isInstanceOf(DataIntegrityViolationException.class);

        UUID ownVersion = insertVersion(own, 1, "publish", null);
        jdbc.update("UPDATE workflow SET published_version_id = ? WHERE id = ?", ownVersion, own);
        assertThat(jdbc.queryForObject("SELECT published_version_id FROM workflow WHERE id = ?", UUID.class, own))
                .isEqualTo(ownVersion);
    }

    @Test
    void aPinToAVersionThatDoesNotExistIsRefused() {
        UUID workflow = insertWorkflow(false);
        insertVersion(workflow, 1, "publish", null);

        assertThatThrownBy(() -> insertPin(UUID.randomUUID(), workflow, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertPin(UUID.randomUUID(), workflow, 1);
    }

    @Test
    void aRollbackRowWithoutRestoredFromIsRefused() {
        UUID workflow = insertWorkflow(false);

        assertThatThrownBy(() -> insertVersion(workflow, 1, "rollback", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(workflow, 1, "manual", 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertVersion(workflow, 1, "rollback", 3);
    }

    @Test
    void theSystemSeedsHaveNoVersionAndNoPointer() {
        for (UUID seed : List.of(DOUYIN_ID, ProductVideoWorkflow.ID)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_version WHERE workflow_id = ?",
                    Integer.class, seed)).isZero();
            assertThat(jdbc.queryForObject("SELECT published_version_id FROM workflow WHERE id = ?", UUID.class, seed))
                    .isNull();
        }
    }

    @Test
    @Transactional
    void theBackfillShapeEqualsTheApplicationSnapshot() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID workflowId = insertWorkflow(owner, true);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertStep(first, workflowId, 1, "Count", "echo", "{\"n\":3,\"x\":2.50}", null);
        insertStep(second, workflowId, 2, null, "echo", null, "{step:1}");

        jdbc.execute(backfillStatement());

        String stored = jdbc.queryForObject(
                "SELECT snapshot::text FROM workflow_version WHERE workflow_id = ?", String.class, workflowId);
        JsonNode storedNode = JSON.readTree(stored);
        Workflow entity = new Workflow(workflowId, owner, "backfill-" + workflowId, true);
        List<WorkflowStep> steps = List.of(
                new WorkflowStep(first, workflowId, (short) 1, "Count", "echo", null, "{\"n\":3,\"x\":2.50}"),
                new WorkflowStep(second, workflowId, (short) 2, null, "echo", "{step:1}", null));
        JsonNode built = WorkflowSnapshots.of(entity, steps);

        assertThat(WorkflowSnapshots.sameDefinition(storedNode, built)).isTrue();
        assertThat(WorkflowSnapshots.sameDefinition(built, storedNode)).isTrue();
        assertThat(jdbc.queryForObject("SELECT released_at IS NOT NULL FROM workflow_version WHERE workflow_id = ?",
                Boolean.class, workflowId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT published_version_id IS NOT NULL FROM workflow WHERE id = ?",
                Boolean.class, workflowId)).isTrue();
    }

    @Test
    @Transactional
    void reRunningTheBackfillAddsNoSecondVersion() throws IOException {
        UUID workflowId = insertWorkflow(true);
        insertStep(UUID.randomUUID(), workflowId, 1, "Only", "echo", null, null);

        jdbc.execute(backfillStatement());
        jdbc.execute(backfillStatement());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_version WHERE workflow_id = ?",
                Integer.class, workflowId)).isEqualTo(1);
    }

    private UUID insertWorkflow(boolean shared) {
        return insertWorkflow(UUID.randomUUID(), shared);
    }

    private UUID insertWorkflow(UUID owner, boolean shared) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status)
                VALUES (?, ?, ?, ?, now(), now(), 'published')
                """, id, owner, "backfill-" + id, shared);
        return id;
    }

    private void insertStep(UUID id, UUID workflowId, int order, String name, String type, String config,
            String inputRef) {
        jdbc.update("""
                INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, workflowId, order, name, type, config, inputRef);
    }

    private UUID insertVersion(UUID workflowId, int version, String source, Integer restoredFrom) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_version (id, workflow_id, version, source, restored_from_version, snapshot,
                                              published_by, published_at)
                VALUES (?, ?, ?, ?, ?, '{"name":"n","steps":[]}'::jsonb, ?, now())
                """, id, workflowId, version, source, restoredFrom, UUID.randomUUID());
        return id;
    }

    private void insertPin(UUID userId, UUID workflowId, int version) {
        jdbc.update("""
                INSERT INTO workflow_version_pin (user_id, workflow_id, pinned_version, pinned_at)
                VALUES (?, ?, ?, now())
                """, userId, workflowId, version);
    }

    /** The statement between the migration's backfill markers, so the test runs the shipped SQL. */
    private static String backfillStatement() throws IOException {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int begin = sql.indexOf("-- backfill:begin");
            int end = sql.indexOf("-- backfill:end");
            assertThat(begin).isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(begin);
            return sql.substring(begin + "-- backfill:begin".length(), end);
        }
    }
}
