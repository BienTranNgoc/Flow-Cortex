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
 * Run-tree columns of {@code workflow_run} through {@link JdbcTemplate} only; every row it
 * inserts is its own, because the Testcontainers database is shared with other ITs.
 */
@Testcontainers
@SpringBootTest
class WorkflowCallSchemaIT extends AbstractWorkflowModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aChildWithDepthThreeIsRefused() {
        Parent parent = parent();

        assertThatThrownBy(() -> insertRun(parent.runId, parent.stepId, (short) 1, (short) 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertRun(parent.runId, parent.stepId, (short) 1, (short) 2);
    }

    @Test
    void aRootWithAParentIsRefused() {
        Parent parent = parent();

        assertThatThrownBy(() -> insertRun(parent.runId, parent.stepId, (short) 1, (short) 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aChildWithoutAParentIsRefused() {
        assertThatThrownBy(() -> insertRun(null, null, null, (short) 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aChildWithoutItsAttemptIsRefused() {
        Parent parent = parent();

        assertThatThrownBy(() -> insertRun(parent.runId, parent.stepId, null, (short) 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondChildForOneStepAttemptIsRefused() {
        Parent parent = parent();
        insertRun(parent.runId, parent.stepId, (short) 1, (short) 1);

        assertThatThrownBy(() -> insertRun(parent.runId, parent.stepId, (short) 1, (short) 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aChildForTheNextAttemptIsAccepted() {
        Parent parent = parent();
        insertRun(parent.runId, parent.stepId, (short) 1, (short) 1);

        UUID next = insertRun(parent.runId, parent.stepId, (short) 2, (short) 1);

        assertThat(jdbc.queryForObject("SELECT parent_step_attempt FROM workflow_run WHERE id = ?",
                Short.class, next)).isEqualTo((short) 2);
    }

    @Test
    void existingRunsReadAsRootsAtDepthZero() {
        UUID workflowId = insertWorkflow();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)
                VALUES (?, ?, ?, 'running', now())
                """, runId, workflowId, UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT call_depth FROM workflow_run WHERE id = ?", Short.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT parent_run_id FROM workflow_run WHERE id = ?", UUID.class, runId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT parent_step_id FROM workflow_run WHERE id = ?", UUID.class, runId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT parent_step_attempt FROM workflow_run WHERE id = ?",
                Short.class, runId)).isNull();
    }

    private record Parent(UUID runId, UUID stepId) {
    }

    private Parent parent() {
        UUID workflowId = insertWorkflow();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)
                VALUES (?, ?, ?, 'running', now())
                """, runId, workflowId, UUID.randomUUID());
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO run_step (id, run_id, step_order, type, status)
                VALUES (?, ?, 1, 'echo', 'running')
                """, stepId, runId);
        return new Parent(runId, stepId);
    }

    private UUID insertWorkflow() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status)
                VALUES (?, ?, ?, false, now(), now(), 'published')
                """, id, UUID.randomUUID(), "call-schema-" + id);
        return id;
    }

    private UUID insertRun(UUID parentRunId, UUID parentStepId, Short attempt, short depth) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at, parent_run_id,
                                          parent_step_id, parent_step_attempt, call_depth)
                VALUES (?, ?, ?, 'running', now(), ?, ?, ?, ?)
                """, id, insertWorkflow(), UUID.randomUUID(), parentRunId, parentStepId, attempt, depth);
        return id;
    }
}
