package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * Published (within tx#1, AFTER the {@code WorkflowRun}+{@code RunStep}s are
 * saved) by {@link WorkflowRunServiceImpl#startRun} once that transaction
 * commits, {@link WorkflowRunOrchestrator} picks it up via a Spring Modulith
 * {@code @ApplicationModuleListener} (design.md D5/R-050) — the async
 * after-commit execution model's entry point.
 */
record WorkflowRunStartedEvent(UUID runId) {
}
