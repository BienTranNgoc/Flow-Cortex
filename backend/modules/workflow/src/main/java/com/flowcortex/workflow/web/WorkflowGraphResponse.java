package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowGraphView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/** Structure only: no step config, no input reference text, no path. */
record WorkflowGraphResponse(UUID workflowId, List<Node> nodes, List<Edge> edges, List<Problem> problems) {

    record Node(UUID id, int stepOrder, String name, String type,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"inline", "remote"}) String mode,
            boolean readsRunInput) {
    }

    record Edge(UUID fromStepId, UUID toStepId,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"previous", "reference", "outputInput"}) String kind) {
    }

    record Problem(UUID stepId, int stepOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"MALFORMED", "UNKNOWN_STEP", "AMBIGUOUS_NAME", "NOT_EARLIER"}) String kind) {
    }

    static WorkflowGraphResponse from(WorkflowGraphView view) {
        return new WorkflowGraphResponse(view.workflowId(),
                view.nodes().stream().map(n -> new Node(n.id(), n.stepOrder(), n.name(), n.type(), n.mode(),
                        n.readsRunInput())).toList(),
                view.edges().stream().map(e -> new Edge(e.fromStepId(), e.toStepId(), e.kind())).toList(),
                view.problems().stream().map(p -> new Problem(p.stepId(), p.stepOrder(), p.kind())).toList());
    }
}
