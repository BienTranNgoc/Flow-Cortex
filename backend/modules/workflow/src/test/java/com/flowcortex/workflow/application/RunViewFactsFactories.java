package com.flowcortex.workflow.application;

import static org.mockito.Mockito.mock;

import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.time.Clock;

/** The view-facts factory the producer unit tests hand their subject: insight switched off, nothing to look up. */
final class RunViewFactsFactories {

    private RunViewFactsFactories() {
    }

    static RunViewFactsFactory insightOff() {
        return new RunViewFactsFactory(mock(WorkflowRepository.class), Clock.systemUTC(), false);
    }
}
