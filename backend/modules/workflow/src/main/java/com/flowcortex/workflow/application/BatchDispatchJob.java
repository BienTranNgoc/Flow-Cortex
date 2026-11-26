package com.flowcortex.workflow.application;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.workflow.infrastructure.BatchRunRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The safety-net tick of batch dispatch: hands every open batch to the dispatcher's pool and returns at once, so
 * the cluster runner's scheduler thread is never held by a pass. A create, resume, cancel or retry nudges the
 * dispatcher sooner; this tick covers a nudge that was lost and the batches that wait for a slot.
 */
@Component
class BatchDispatchJob implements ClusterJob {

    static final int CANDIDATES = 50;

    private final BatchRunRepository batches;
    private final BatchDispatcher dispatcher;
    private final boolean enabled;
    private final Duration interval;

    BatchDispatchJob(BatchRunRepository batches, BatchDispatcher dispatcher,
            @Value("${flowcortex.workflow.batch.dispatch-enabled:true}") boolean enabled,
            @Value("${flowcortex.workflow.batch.dispatch-interval:PT5S}") Duration interval) {
        this.batches = batches;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "workflow.batch-dispatch";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void run() {
        if (!enabled) {
            return;
        }
        batches.findOpenForDispatch(PageRequest.of(0, CANDIDATES)).forEach(dispatcher::request);
    }
}
