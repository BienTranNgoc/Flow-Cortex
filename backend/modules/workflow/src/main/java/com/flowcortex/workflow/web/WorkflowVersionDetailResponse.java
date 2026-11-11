package com.flowcortex.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.workflow.api.VersionSnapshotView;
import com.flowcortex.workflow.api.WorkflowVersionDetailView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/**
 * A version with its snapshot. For a member other than the owner the snapshot is a skeleton: {@code config} and
 * {@code inputRef} of every step are null.
 */
record WorkflowVersionDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) WorkflowVersionResponse version,
        @Schema(requiredMode = RequiredMode.REQUIRED) SnapshotResponse snapshot) {

    static WorkflowVersionDetailResponse from(WorkflowVersionDetailView view) {
        VersionSnapshotView snapshot = view.snapshot();
        return new WorkflowVersionDetailResponse(WorkflowVersionResponse.from(view.version()),
                new SnapshotResponse(snapshot.name(), snapshot.skeleton(),
                        snapshot.steps().stream().map(SnapshotStepResponse::from).toList()));
    }

    record SnapshotResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean skeleton,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<SnapshotStepResponse> steps) {
    }

    record SnapshotStepResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
            @Schema(nullable = true) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) String type,
            @Schema(nullable = true) String inputRef,
            @Schema(type = "object", nullable = true) JsonNode config) {

        static SnapshotStepResponse from(VersionSnapshotView.Step step) {
            return new SnapshotStepResponse(step.id(), step.stepOrder(), step.name(), step.type(), step.inputRef(),
                    step.config());
        }
    }
}
