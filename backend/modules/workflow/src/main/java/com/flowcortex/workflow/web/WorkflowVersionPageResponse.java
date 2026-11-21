package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowVersionPageView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** Newest first; pass {@code nextBeforeVersion} as {@code beforeVersion} for the next page, null on the last. */
record WorkflowVersionPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<WorkflowVersionResponse> items,
        @Schema(nullable = true) Integer nextBeforeVersion) {

    static WorkflowVersionPageResponse from(WorkflowVersionPageView view) {
        return new WorkflowVersionPageResponse(view.items().stream().map(WorkflowVersionResponse::from).toList(),
                view.nextBeforeVersion());
    }
}
