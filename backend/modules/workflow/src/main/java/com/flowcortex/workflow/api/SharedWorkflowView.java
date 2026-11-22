package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * What a share link opens: the workflow's id, name, its author's display name and its steps' order, name and
 * type. No key, owner id, step config or cost.
 */
public record SharedWorkflowView(UUID id, String name, String authorName, List<Step> steps) {

    /** One step as a link recipient sees it. */
    public record Step(int stepOrder, String name, String type) {
    }
}
