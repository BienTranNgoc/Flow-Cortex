package com.flowcortex.workflow.application;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The threads the model calls for QA questions run on. Deliberately not an
 * {@link java.util.concurrent.Executor}: an Executor bean would switch off Boot's shared
 * {@code applicationTaskExecutor}.
 */
interface QaQuestionsPool {

    /** @throws TaskRejectedException when every thread is busy and the queue is full */
    void execute(Runnable task);

    /** {@code size} calls run at once and {@code queueCapacity} wait; the next one is rejected. */
    @Component("qaQuestionsThreads")
    final class Threads implements QaQuestionsPool, DisposableBean {

        private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        Threads(@Value("${flowcortex.workflow.qa-review.pool.size:2}") int size,
                @Value("${flowcortex.workflow.qa-review.pool.queue-capacity:50}") int queueCapacity) {
            if (size < 1 || queueCapacity < 0) {
                throw new IllegalStateException("flowcortex.workflow.qa-review.pool needs size >= 1 and "
                        + "queue-capacity >= 0: size=" + size + " queue-capacity=" + queueCapacity);
            }
            executor.setThreadNamePrefix("qa-questions-");
            executor.setCorePoolSize(size);
            executor.setMaxPoolSize(size);
            executor.setQueueCapacity(queueCapacity);
            // The trace follows the task, as on the shared pool.
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(30);
            executor.initialize();
        }

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }

        @Override
        public void destroy() {
            executor.shutdown();
        }
    }
}
