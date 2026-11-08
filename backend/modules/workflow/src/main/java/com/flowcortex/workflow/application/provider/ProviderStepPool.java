package com.flowcortex.workflow.application.provider;

import org.springframework.core.task.TaskRejectedException;

/**
 * The bounded threads the provider-backed inline steps run on. Deliberately not an
 * {@link java.util.concurrent.Executor}: an Executor bean would switch off Boot's shared
 * {@code applicationTaskExecutor}.
 */
public interface ProviderStepPool {

    /** @throws TaskRejectedException when every thread is busy and the queue is full */
    void execute(Runnable task);
}
