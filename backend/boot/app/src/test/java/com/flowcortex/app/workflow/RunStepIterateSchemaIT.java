package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Parent columns and keys of {@code run_step} through {@link JdbcTemplate} only; every row it
 * inserts is its own, because the Testcontainers database is shared with other ITs.
 */
@Testcontainers
@SpringBootTest
class RunStepIterateSchemaIT extends AbstractWorkflowModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void existingRowsReadAsTopLevel() {
        UUID runId = insertRun();
        UUID stepId = insertTopLevel(runId, 1);

        assertThat(jdbc.queryForObject("SELECT parent_step_id FROM run_step WHERE id = ?", UUID.class, stepId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT parent_attempt FROM run_step WHERE id = ?", Short.class, stepId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT item_index FROM run_step WHERE id = ?", Short.class, stepId))
                .isNull();
    }

    @Test
    void partialParentColumnsAreRefused() {
        UUID runId = insertRun();
        UUID parentId = insertTopLevel(runId, 1);

        assertThatThrownBy(() -> insertChild(runId, 1, parentId, (short) 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChild(runId, 1, parentId, null, (short) 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChild(runId, 1, null, (short) 1, (short) 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChild(runId, 1, parentId, (short) 0, (short) 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void itemIndexAboveFortyNineIsRefused() {
        UUID runId = insertRun();
        UUID parentId = insertTopLevel(runId, 1);

        assertThatThrownBy(() -> insertChild(runId, 1, parentId, (short) 1, (short) 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChild(runId, 1, parentId, (short) 1, (short) -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertChild(runId, 1, parentId, (short) 1, (short) 49);
    }

    @Test
    void duplicateItemOfOneParentAttemptIsRefused() {
        UUID runId = insertRun();
        UUID parentId = insertTopLevel(runId, 1);
        insertChild(runId, 1, parentId, (short) 1, (short) 0);

        assertThatThrownBy(() -> insertChild(runId, 1, parentId, (short) 1, (short) 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameItemForTheNextParentAttemptIsAccepted() {
        UUID runId = insertRun();
        UUID parentId = insertTopLevel(runId, 1);
        insertChild(runId, 1, parentId, (short) 1, (short) 0);

        UUID next = insertChild(runId, 1, parentId, (short) 2, (short) 0);

        assertThat(jdbc.queryForObject("SELECT parent_attempt FROM run_step WHERE id = ?", Short.class, next))
                .isEqualTo((short) 2);
    }

    @Test
    void childrenShareTheirParentsOrder() {
        UUID runId = insertRun();
        UUID parentId = insertTopLevel(runId, 2);

        insertChild(runId, 2, parentId, (short) 1, (short) 0);
        insertChild(runId, 2, parentId, (short) 1, (short) 1);
        insertChild(runId, 2, parentId, (short) 1, (short) 2);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_step WHERE run_id = ? AND step_order = 2",
                Integer.class, runId)).isEqualTo(4);
    }

    @Test
    void secondTopLevelStepWithOneOrderIsRefused() {
        UUID runId = insertRun();
        UUID parentId = insertTopLevel(runId, 2);
        insertChild(runId, 2, parentId, (short) 1, (short) 0);

        assertThatThrownBy(() -> insertTopLevel(runId, 2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theOldOrderConstraintIsGone() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_run_step_order'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'run_step' AND indexname IN ('uq_run_step_top_order', 'uq_run_step_child_item')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void iterateHasAFreePricingRow() {
        assertThat(jdbc.queryForObject(
                "SELECT estimator_kind FROM capability_pricing WHERE capability = 'iterate'", String.class))
                .isEqualTo("free");
    }

    private UUID insertRun() {
        UUID workflowId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status)
                VALUES (?, ?, ?, false, now(), now(), 'published')
                """, workflowId, UUID.randomUUID(), "iterate-schema-" + workflowId);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)
                VALUES (?, ?, ?, 'running', now())
                """, runId, workflowId, UUID.randomUUID());
        return runId;
    }

    private UUID insertTopLevel(UUID runId, int order) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO run_step (id, run_id, step_order, type, status)
                VALUES (?, ?, ?, 'iterate', 'running')
                """, id, runId, order);
        return id;
    }

    private UUID insertChild(UUID runId, int order, UUID parentId, Short parentAttempt, Short itemIndex) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO run_step (id, run_id, step_order, type, status, parent_step_id, parent_attempt, item_index)
                VALUES (?, ?, ?, 'echo', 'pending', ?, ?, ?)
                """, id, runId, order, parentId, parentAttempt, itemIndex);
        return id;
    }
}
