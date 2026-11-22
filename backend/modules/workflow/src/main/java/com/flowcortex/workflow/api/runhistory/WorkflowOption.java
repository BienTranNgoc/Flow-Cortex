package com.flowcortex.workflow.api.runhistory;

import java.util.UUID;

/** A workflow the member has a run of, offered by the history filter. */
public record WorkflowOption(UUID id, String name) {
}
