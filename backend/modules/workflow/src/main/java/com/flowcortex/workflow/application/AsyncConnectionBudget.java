package com.flowcortex.workflow.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the connection pool cannot give every async task its worst case at once. A workflow.call claim
 * holds four connections on one thread (listener, advance, claim, fact reads); a pool below that product lets a burst
 * of tasks each wait for a connection another holds, while still holding the parent run's row lock.
 */
@Component
final class AsyncConnectionBudget {

    static final int CONNECTIONS_PER_TASK = 4;

    AsyncConnectionBudget(@Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
            @Value("${spring.task.execution.pool.max-size:2147483647}") int taskMaxSize) {
        long required = required(taskMaxSize);
        if (poolSize < required) {
            throw new IllegalStateException("spring.datasource.hikari.maximum-pool-size is " + poolSize + " but "
                    + CONNECTIONS_PER_TASK + " x spring.task.execution.pool.max-size (" + taskMaxSize + ") is "
                    + required + ": an async task can hold " + CONNECTIONS_PER_TASK
                    + " connections at once; raise the pool or lower the task pool");
        }
    }

    static long required(int taskMaxSize) {
        return (long) CONNECTIONS_PER_TASK * taskMaxSize;
    }
}
