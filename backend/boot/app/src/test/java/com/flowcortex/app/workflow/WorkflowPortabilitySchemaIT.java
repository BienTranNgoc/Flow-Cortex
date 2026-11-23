package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Defaults and constraints of the fork columns and the import review table, through
 * {@link JdbcTemplate} only. The Testcontainers database is shared with other ITs, so every
 * assertion is scoped to the rows the test inserted (plus the two seeds).
 */
@Testcontainers
@SpringBootTest
class WorkflowPortabilitySchemaIT extends AbstractWorkflowModuleIT {

    private static final UUID DOUYIN_ID = UUID.fromString("f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyWorkflowExistingAtUpgradeHasForkingOff() {
        UUID legacy = insertWorkflow();

        assertThat(jdbc.queryForObject("SELECT allow_fork FROM workflow WHERE id = ?", Boolean.class, legacy))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT fork_count FROM workflow WHERE id = ?", Integer.class, legacy))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT forked_from_id FROM workflow WHERE id = ?", UUID.class, legacy))
                .isNull();
        for (UUID seed : List.of(DOUYIN_ID, ProductVideoWorkflow.ID)) {
            assertThat(jdbc.queryForObject("SELECT allow_fork FROM workflow WHERE id = ?", Boolean.class, seed))
                    .isFalse();
        }
    }

    @Test
    void aNegativeForkCountIsRefused() {
        UUID workflow = insertWorkflow();

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow SET fork_count = -1 WHERE id = ?", workflow))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE workflow SET fork_count = 3 WHERE id = ?", workflow);
        assertThat(jdbc.queryForObject("SELECT fork_count FROM workflow WHERE id = ?", Integer.class, workflow))
                .isEqualTo(3);
    }

    @Test
    void aForkedFromIdNamingNoWorkflowIsRefused() {
        UUID source = insertWorkflow();
        UUID fork = insertWorkflow();

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow SET forked_from_id = ? WHERE id = ?",
                UUID.randomUUID(), fork)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE workflow SET forked_from_id = ? WHERE id = ?", source, fork);
        assertThat(jdbc.queryForObject("SELECT forked_from_id FROM workflow WHERE id = ?", UUID.class, fork))
                .isEqualTo(source);
    }

    @Test
    void anImportedReviewMustHaveNoArtifactAndNoReview() {
        UUID workflow = insertWorkflow();

        assertThatThrownBy(() -> insertReview(UUID.randomUUID(), "{}", "{}", workflow, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReview(UUID.randomUUID(), "{}", null, workflow, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertReview(UUID.randomUUID(), null, null, workflow, true);
    }

    @Test
    void anOpenReviewMustHaveArtifactAndReview() {
        assertThatThrownBy(() -> insertReview(UUID.randomUUID(), null, null, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReview(UUID.randomUUID(), "{}", null, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReview(UUID.randomUUID(), null, "{}", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertReview(UUID.randomUUID(), "{}", "{}", null, false);
    }

    @Test
    void workflowIdAndImportedAtAreSetTogether() {
        UUID workflow = insertWorkflow();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO workflow_import_review (token, user_id, outbound, artifact_bytes, created_at,
                                                    expires_at, workflow_id, imported_at)
                VALUES (?, ?, false, 10, now(), now() + interval '1 hour', ?, NULL)
                """, UUID.randomUUID(), UUID.randomUUID(), workflow))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO workflow_import_review (token, user_id, artifact, review, outbound, artifact_bytes,
                                                    created_at, expires_at, workflow_id, imported_at)
                VALUES (?, ?, '{}'::jsonb, '{}'::jsonb, false, 10, now(), now() + interval '1 hour', NULL, now())
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmptyArtifactSizeIsRefused() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO workflow_import_review (token, user_id, artifact, review, outbound, artifact_bytes,
                                                    created_at, expires_at)
                VALUES (?, ?, '{}'::jsonb, '{}'::jsonb, false, 0, now(), now() + interval '1 hour')
                """, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertWorkflow() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status)
                VALUES (?, ?, ?, false, now(), now(), 'draft')
                """, id, UUID.randomUUID(), "portability-" + id);
        return id;
    }

    private void insertReview(UUID token, String artifact, String review, UUID workflowId, boolean imported) {
        jdbc.update("""
                INSERT INTO workflow_import_review (token, user_id, artifact, review, outbound, artifact_bytes,
                                                    created_at, expires_at, workflow_id, imported_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, false, 10, now(), now() + interval '1 hour', ?,
                        CASE WHEN ? THEN now() END)
                """, token, UUID.randomUUID(), artifact, review, workflowId, imported);
    }
}
