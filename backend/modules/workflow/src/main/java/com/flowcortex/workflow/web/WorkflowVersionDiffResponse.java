package com.flowcortex.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.workflow.api.DiffSide;
import com.flowcortex.workflow.api.VersionDiffView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/**
 * The difference between two sides. For a member other than the owner {@code skeleton} is true and {@code before}
 * and {@code after} of every step are null: only the names of the changed fields remain.
 */
record WorkflowVersionDiffResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) DiffSideResponse source,
        @Schema(requiredMode = RequiredMode.REQUIRED) DiffSideResponse target,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean skeleton,
        @Schema(requiredMode = RequiredMode.REQUIRED) NameChangeResponse name,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<StepChangeResponse> steps) {

    static WorkflowVersionDiffResponse from(VersionDiffView view) {
        return new WorkflowVersionDiffResponse(DiffSideResponse.from(view.source()),
                DiffSideResponse.from(view.target()), view.skeleton(),
                new NameChangeResponse(view.name().changed(), view.name().before(), view.name().after()),
                view.steps().stream().map(StepChangeResponse::from).toList());
    }

    record DiffSideResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String ref,
            @Schema(nullable = true) Integer version) {

        static DiffSideResponse from(DiffSide side) {
            return new DiffSideResponse(side.ref(), side.version());
        }
    }

    record NameChangeResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean changed,
            @Schema(nullable = true) String before,
            @Schema(nullable = true) String after) {
    }

    record StepChangeResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String stepId,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"added", "removed", "modified", "moved"})
            String status,
            @Schema(nullable = true) Integer fromOrder,
            @Schema(nullable = true) Integer toOrder,
            @Schema(nullable = true) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> changedFields,
            @Schema(nullable = true) Map<String, JsonNode> before,
            @Schema(nullable = true) Map<String, JsonNode> after) {

        static StepChangeResponse from(VersionDiffView.StepChange change) {
            return new StepChangeResponse(change.stepId(), change.status(), change.fromOrder(), change.toOrder(),
                    change.name(), change.type(), change.changedFields(), change.before(), change.after());
        }
    }
}
