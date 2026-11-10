package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.SharedWorkflowView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** What a share link shows: no key, owner id, step configuration or cost. */
record SharedWorkflowResponse(UUID id, String name, @Schema(nullable = true) String authorName, List<Step> steps) {

    record Step(int stepOrder, String name, String type) {
    }

    static SharedWorkflowResponse from(SharedWorkflowView view) {
        return new SharedWorkflowResponse(view.id(), view.name(), view.authorName(),
                view.steps().stream().map(step -> new Step(step.stepOrder(), step.name(), step.type())).toList());
    }
}
