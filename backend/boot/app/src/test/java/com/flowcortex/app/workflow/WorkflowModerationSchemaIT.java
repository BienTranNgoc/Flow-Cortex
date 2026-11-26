package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The moderation column and the report and history tables against the real schema. Every test runs in
 * a rolled-back transaction (a refused statement aborts it, so each refusal is its own test).
 */
@Testcontainers
@SpringBootTest
@Transactional
class WorkflowModerationSchemaIT extends AbstractWorkflowModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertWorkflow() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                + " VALUES (?, ?, 'moderation-schema-it', false, now(), now())", id, UUID.randomUUID());
        return id;
    }

    private void insertPendingReport(UUID id, UUID workflowId, UUID reporterId, String reason) {
        jdbc.update("INSERT INTO workflow_report (id, workflow_id, reporter_id, reason, created_at)"
                + " VALUES (?, ?, ?, ?, now())", id, workflowId, reporterId, reason);
    }

    private void insertAction(UUID workflowId, UUID reportId, String source, String action, String reason) {
        jdbc.update("INSERT INTO workflow_moderation_action (id, workflow_id, report_id, source, action,"
                + " moderator_id, reason, moderation_status, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'hidden', now())",
                UUID.randomUUID(), workflowId, reportId, source, action, UUID.randomUUID(), reason);
    }

    @Test
    void existingWorkflowsAreVisibleAfterMigration() {
        UUID workflowId = insertWorkflow();

        assertThat(jdbc.queryForObject("SELECT moderation_status FROM workflow WHERE id = ?", String.class,
                workflowId)).isEqualTo("visible");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow WHERE moderation_status <> 'visible'",
                Integer.class)).isZero();
    }

    @Test
    void unknownModerationStatusIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow SET moderation_status = 'banned' WHERE id = ?",
                workflowId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_moderation_status");
    }

    @Test
    void secondReportOfOnePairIsRefused() {
        UUID workflowId = insertWorkflow();
        UUID reporterId = UUID.randomUUID();
        insertPendingReport(UUID.randomUUID(), workflowId, reporterId, "spam");

        assertThatThrownBy(() -> insertPendingReport(UUID.randomUUID(), workflowId, reporterId, "scam"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_workflow_report_reporter");
    }

    @Test
    void unknownReasonIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertPendingReport(UUID.randomUUID(), workflowId, UUID.randomUUID(), "rude"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("workflow_report_reason_check");
    }

    @Test
    void pendingReportWithReviewerIsRefused() {
        UUID workflowId = insertWorkflow();
        UUID reportId = UUID.randomUUID();
        insertPendingReport(reportId, workflowId, UUID.randomUUID(), "spam");

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow_report SET reviewer_id = ? WHERE id = ?",
                UUID.randomUUID(), reportId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_report_review");
    }

    @Test
    void directEntryWithReportIdIsRefused() {
        UUID workflowId = insertWorkflow();
        UUID reportId = UUID.randomUUID();
        insertPendingReport(reportId, workflowId, UUID.randomUUID(), "spam");

        assertThatThrownBy(() -> insertAction(workflowId, reportId, "direct", "hide", "takedown"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wma_source");
    }

    @Test
    void directDismissIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertAction(workflowId, null, "direct", "dismiss", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wma_direct_action");
    }

    @Test
    void hideWithoutReasonIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertAction(workflowId, null, "direct", "hide", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wma_reason");
    }

    @Test
    void hideWithBlankReasonIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertAction(workflowId, null, "direct", "hide", "   "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wma_reason");
    }

    @Test
    void deletingAReportKeepsItsHistoryWithReportIdNull() {
        UUID workflowId = insertWorkflow();
        UUID reportId = UUID.randomUUID();
        insertPendingReport(reportId, workflowId, UUID.randomUUID(), "harmful");
        jdbc.update("UPDATE workflow_report SET status = 'hidden', resolution = 'hide', reviewer_id = ?,"
                + " reviewed_at = now() WHERE id = ?", UUID.randomUUID(), reportId);
        insertAction(workflowId, reportId, "report", "hide", "harmful content");

        jdbc.update("DELETE FROM workflow_report WHERE id = ?", reportId);

        Map<String, Object> history = jdbc.queryForMap(
                "SELECT source, report_id FROM workflow_moderation_action WHERE workflow_id = ?", workflowId);
        assertThat(history).containsEntry("source", "report").containsEntry("report_id", null);
    }
}
