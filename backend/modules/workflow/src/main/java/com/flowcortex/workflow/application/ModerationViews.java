package com.flowcortex.workflow.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What staff read of the report queue and the moderation history. Status values are the lowercase codes stored in
 * the database; a person whose account no longer exists has a null name and email.
 */
public final class ModerationViews {

    private ModerationViews() {}

    public record Person(UUID id, String name, String email) {}

    public record WorkflowRef(
            UUID id, String name, String status, String visibility, String moderationStatus, boolean deleted,
            Person owner) {}

    /** {@code reviewer} is null while the report is pending. */
    public record ReportView(
            UUID id, String reason, String details, String status, String resolution, Instant reviewedAt,
            Instant createdAt, Person reporter, Person reviewer, WorkflowRef workflow) {}

    /** One entry of a workflow's moderation history; {@code moderationStatus} is the status after the action. */
    public record HistoryEntry(
            UUID id, String action, String source, UUID reportId, UUID moderatorId, String moderatorName,
            String reason, String internalNote, String moderationStatus, Instant createdAt) {}

    public record ReportDetailView(ReportView report, String internalNote, List<HistoryEntry> history) {}

    /** The workflow an action settled on and its moderation status afterwards. */
    public record ModerationOutcome(UUID workflowId, String moderationStatus) {}
}
