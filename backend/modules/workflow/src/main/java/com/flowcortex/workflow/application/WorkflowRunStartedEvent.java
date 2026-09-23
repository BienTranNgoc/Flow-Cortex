package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * Published within tx#1 (after the run + steps are saved), once {@link
 * WorkflowRunServiceImpl#startRun}'s transaction commits. {@link WorkflowRunOrchestrator} picks
 * it up via a Spring Modulith {@code @ApplicationModuleListener} — the async after-commit
 * execution entry point.
 */
record WorkflowRunStartedEvent(UUID runId) {
}
