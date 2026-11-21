package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.AdminWorkflowRow;
import com.flowcortex.workflow.api.DayCount;
import com.flowcortex.workflow.api.WorkflowReportView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire shapes of the admin workflow list and report. */
final class AdminWorkflowResponses {

    private AdminWorkflowResponses() {
    }

    /**
     * {@code ownerEmail} is {@code null} when the owner no longer exists; {@code lastRunAt} is {@code null}
     * for a workflow never run; {@code runsLast7Days} counts runs started in the 7 days before the request.
     */
    record AdminWorkflowResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID ownerId,
            String ownerEmail,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"draft", "published"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean shared,
            @Schema(requiredMode = RequiredMode.REQUIRED) int steps,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runs,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runsLast7Days,
            Instant lastRunAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

        static AdminWorkflowResponse from(AdminWorkflowRow row) {
            return new AdminWorkflowResponse(row.id(), row.name(), row.ownerId(), row.ownerEmail(), row.status(),
                    row.shared(), row.steps(), row.runs(), row.runsLast7Days(), row.lastRunAt(), row.createdAt(),
                    row.updatedAt());
        }
    }

    record DayCountResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate day,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runs,
            @Schema(requiredMode = RequiredMode.REQUIRED) long failed) {

        static DayCountResponse from(DayCount count) {
            return new DayCountResponse(count.day(), count.runs(), count.failed());
        }
    }

    /**
     * {@code countsByStatus} holds every run status (lowercase), zero for none; {@code series} is the last
     * 30 local days, oldest first.
     */
    record WorkflowReportResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) AdminWorkflowResponse workflow,
            @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, Long> countsByStatus,
            Instant lastRunAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<DayCountResponse> series) {

        static WorkflowReportResponse from(WorkflowReportView view) {
            return new WorkflowReportResponse(AdminWorkflowResponse.from(view.workflow()), view.countsByStatus(),
                    view.lastRunAt(), view.series().stream().map(DayCountResponse::from).toList());
        }
    }
}
