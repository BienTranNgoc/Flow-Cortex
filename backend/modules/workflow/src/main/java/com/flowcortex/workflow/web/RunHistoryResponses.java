package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.runhistory.RunHistoryItem;
import com.flowcortex.workflow.api.runhistory.RunTrayView;
import com.flowcortex.workflow.api.runhistory.TopWorkflowView;
import com.flowcortex.workflow.api.runhistory.UsageDay;
import com.flowcortex.workflow.api.runhistory.UsageSummaryView;
import com.flowcortex.workflow.api.runhistory.WorkflowOption;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The wire shapes of the member run-history endpoints. */
final class RunHistoryResponses {

    private RunHistoryResponses() {
    }

    /**
     * {@code status} is the lowercase wire value; {@code durationSeconds} is null until the run finishes and
     * {@code creditsCharged} while credits are switched off.
     */
    record RunHistoryRowResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
            String title,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"running", "waiting_approval",
                    "completed", "error", "cancelling", "cancelled"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean favorite,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant startedAt,
            Instant finishedAt, Long durationSeconds, Long creditsCharged) {

        static RunHistoryRowResponse from(RunHistoryItem item) {
            return new RunHistoryRowResponse(item.id(), item.workflowId(), item.workflowName(), item.title(),
                    item.status(), item.favorite(), item.startedAt(), item.finishedAt(), item.durationSeconds(),
                    item.creditsCharged());
        }
    }

    record WorkflowOptionResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name) {

        static WorkflowOptionResponse from(WorkflowOption option) {
            return new WorkflowOptionResponse(option.id(), option.name());
        }
    }

    record RunTrayResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<RunHistoryRowResponse> active,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<RunHistoryRowResponse> recent) {

        static RunTrayResponse from(RunTrayView view) {
            return new RunTrayResponse(view.active().stream().map(RunHistoryRowResponse::from).toList(),
                    view.recent().stream().map(RunHistoryRowResponse::from).toList());
        }
    }

    record UsageDayResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate day,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runs,
            @Schema(requiredMode = RequiredMode.REQUIRED) long failed, Long credits) {

        static UsageDayResponse from(UsageDay day) {
            return new UsageDayResponse(day.day(), day.runs(), day.failed(), day.credits());
        }
    }

    record TopWorkflowResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runs, Long credits, Double averageCredits) {

        static TopWorkflowResponse from(TopWorkflowView view) {
            return new TopWorkflowResponse(view.workflowId(), view.name(), view.runs(), view.credits(),
                    view.averageCredits());
        }
    }

    /**
     * The window runs from {@code from} to {@code to} inclusive in the product's local days;
     * {@code failureRate} is a percentage with one decimal. {@code creditsSpent} and {@code balance} are null
     * while credits are switched off.
     */
    record UsageSummaryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) int days,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate from,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate to,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runs,
            @Schema(requiredMode = RequiredMode.REQUIRED) long failed,
            @Schema(requiredMode = RequiredMode.REQUIRED) double failureRate, Long creditsSpent, Long balance,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<UsageDayResponse> series,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<TopWorkflowResponse> topWorkflows) {

        static UsageSummaryResponse from(UsageSummaryView view) {
            return new UsageSummaryResponse(view.days(), view.from(), view.to(), view.runs(), view.failed(),
                    view.failureRate(), view.creditsSpent(), view.balance(),
                    view.series().stream().map(UsageDayResponse::from).toList(),
                    view.topWorkflows().stream().map(TopWorkflowResponse::from).toList());
        }
    }
}
