package com.flowcortex.workflow.infrastructure;

import java.util.UUID;

/** A workflow's id and name, as offered by a run-history filter. */
public record WorkflowChoice(UUID id, String name) {
}
