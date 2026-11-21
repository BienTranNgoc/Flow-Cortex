package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.media.api.RunInputsBound;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Queues a run's outputs when it completes and its inputs when they are bound. An exception
 * escapes only when the queue row cannot be written, so the publication is redelivered.
 */
@Component
class AssetRegistrationListener {

    private final AssetRegistry registry;

    AssetRegistrationListener(AssetRegistry registry) {
        this.registry = registry;
    }

    @ApplicationModuleListener
    void on(WorkflowRunCompleted event) {
        registry.announce(SourceType.RUN_OUTPUTS, event.runId(), event.ownerId());
    }

    @ApplicationModuleListener
    void on(RunInputsBound event) {
        registry.announce(SourceType.RUN_INPUTS, event.runId(), event.ownerId());
    }
}
