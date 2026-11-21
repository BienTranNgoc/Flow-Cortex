package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Structure of a workflow's steps for a viewer: who feeds whom and which references are broken.
 * Carries no step config, no {@code inputRef} text and no reference path.
 */
public record WorkflowGraphView(UUID workflowId, List<Node> nodes, List<Edge> edges, List<Problem> problems) {

    /** {@code readsRunInput} is true when the step reads what the run was started with. */
    public record Node(UUID id, int stepOrder, String name, String type, String mode, boolean readsRunInput) {
    }

    /** {@code kind} is {@code previous}, {@code reference} or {@code outputInput}. */
    public record Edge(UUID fromStepId, UUID toStepId, String kind) {
    }

    /** {@code kind} is {@code MALFORMED}, {@code UNKNOWN_STEP}, {@code AMBIGUOUS_NAME} or {@code NOT_EARLIER}. */
    public record Problem(UUID stepId, int stepOrder, String kind) {
    }
}
