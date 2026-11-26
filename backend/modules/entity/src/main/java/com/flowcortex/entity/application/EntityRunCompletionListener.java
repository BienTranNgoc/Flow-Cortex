package com.flowcortex.entity.application;

import com.flowcortex.entity.infrastructure.EntityRunStore;
import com.flowcortex.workflow.api.RunRecords;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import java.time.Clock;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Completes the usage rows of a run when it completes. A failure escapes, so the publication is redelivered under
 * the event's own retry policy.
 */
@Component
class EntityRunCompletionListener {

    private final EntityRunStore store;
    private final RunRecords runs;
    private final Clock clock;

    EntityRunCompletionListener(EntityRunStore store, RunRecords runs, Clock clock) {
        this.store = store;
        this.runs = runs;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(WorkflowRunCompleted event) {
        Instant at = runs.find(event.runId()).map(run -> EntityRunUsageImpl.finishTime(run, clock))
                .orElseGet(clock::instant);
        store.markCompleted(event.runId(), at);
    }
}
