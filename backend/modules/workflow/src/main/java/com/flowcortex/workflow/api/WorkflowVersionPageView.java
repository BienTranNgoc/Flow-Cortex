package com.flowcortex.workflow.api;

import java.util.List;

/** One page of versions, newest first; {@code nextBeforeVersion} is null on the last page. */
public record WorkflowVersionPageView(List<WorkflowVersionView> items, Integer nextBeforeVersion) {
}
