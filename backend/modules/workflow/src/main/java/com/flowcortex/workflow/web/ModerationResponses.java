package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.ModerationViews.HistoryEntry;
import com.flowcortex.workflow.application.ModerationViews.ModerationOutcome;
import com.flowcortex.workflow.application.ModerationViews.Person;
import com.flowcortex.workflow.application.ModerationViews.ReportDetailView;
import com.flowcortex.workflow.application.ModerationViews.ReportView;
import com.flowcortex.workflow.application.ModerationViews.WorkflowRef;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the report queue and the moderation history. Status values are the exact lowercase wire values.
 * A name or an email is null when the account no longer exists.
 */
final class ModerationResponses {

    private ModerationResponses() {
    }

    record ModerationPersonResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id, String name, String email) {

        static ModerationPersonResponse from(Person person) {
            return new ModerationPersonResponse(person.id(), person.name(), person.email());
        }
    }

    record ModerationReviewerResponse(@Schema(requiredMode = RequiredMode.REQUIRED) UUID id, String name) {

        static ModerationReviewerResponse from(Person person) {
            return person == null ? null : new ModerationReviewerResponse(person.id(), person.name());
        }
    }

    record ReportedWorkflowResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"draft", "published"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"private", "unlisted", "public"})
            String visibility,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"visible", "hidden", "removed"})
            String moderationStatus,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean deleted,
            @Schema(requiredMode = RequiredMode.REQUIRED) ModerationPersonResponse owner) {

        static ReportedWorkflowResponse from(WorkflowRef workflow) {
            return new ReportedWorkflowResponse(workflow.id(), workflow.name(), workflow.status(),
                    workflow.visibility(), workflow.moderationStatus(), workflow.deleted(),
                    ModerationPersonResponse.from(workflow.owner()));
        }
    }

    /** {@code reviewer}, {@code resolution} and {@code reviewedAt} are null while the report is pending. */
    record WorkflowReportRowResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String reason,
            String details,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"pending", "dismissed", "resolved", "hidden", "removed"}) String status,
            @Schema(allowableValues = {"dismiss", "resolve", "hide", "remove", "restore"}) String resolution,
            Instant reviewedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) ModerationPersonResponse reporter,
            ModerationReviewerResponse reviewer,
            @Schema(requiredMode = RequiredMode.REQUIRED) ReportedWorkflowResponse workflow) {

        static WorkflowReportRowResponse from(ReportView report) {
            return new WorkflowReportRowResponse(report.id(), report.reason(), report.details(), report.status(),
                    report.resolution(), report.reviewedAt(), report.createdAt(),
                    ModerationPersonResponse.from(report.reporter()), ModerationReviewerResponse.from(report.reviewer()),
                    ReportedWorkflowResponse.from(report.workflow()));
        }
    }

    /**
     * {@code moderationStatus} is the workflow's status after the action; a null {@code reason} is a dismiss or
     * resolve sent without one.
     */
    record ModerationHistoryEntryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"dismiss", "resolve", "hide", "remove", "restore"}) String action,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"report", "direct"}) String source,
            UUID reportId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID moderatorId,
            String moderatorName,
            String reason,
            String internalNote,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"visible", "hidden", "removed"})
            String moderationStatus,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

        static ModerationHistoryEntryResponse from(HistoryEntry entry) {
            return new ModerationHistoryEntryResponse(entry.id(), entry.action(), entry.source(), entry.reportId(),
                    entry.moderatorId(), entry.moderatorName(), entry.reason(), entry.internalNote(),
                    entry.moderationStatus(), entry.createdAt());
        }
    }

    /** The report row plus its internal note and the workflow's history, newest first. */
    record WorkflowReportDetailResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String reason,
            String details,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"pending", "dismissed", "resolved", "hidden", "removed"}) String status,
            @Schema(allowableValues = {"dismiss", "resolve", "hide", "remove", "restore"}) String resolution,
            Instant reviewedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) ModerationPersonResponse reporter,
            ModerationReviewerResponse reviewer,
            @Schema(requiredMode = RequiredMode.REQUIRED) ReportedWorkflowResponse workflow,
            String internalNote,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ModerationHistoryEntryResponse> history) {

        static WorkflowReportDetailResponse from(ReportDetailView detail) {
            WorkflowReportRowResponse row = WorkflowReportRowResponse.from(detail.report());
            return new WorkflowReportDetailResponse(row.id(), row.reason(), row.details(), row.status(),
                    row.resolution(), row.reviewedAt(), row.createdAt(), row.reporter(), row.reviewer(),
                    row.workflow(), detail.internalNote(),
                    detail.history().stream().map(ModerationHistoryEntryResponse::from).toList());
        }
    }

    record ModerationOutcomeResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"visible", "hidden", "removed"})
            String moderationStatus) {

        static ModerationOutcomeResponse from(ModerationOutcome outcome) {
            return new ModerationOutcomeResponse(outcome.workflowId(), outcome.moderationStatus());
        }
    }
}
