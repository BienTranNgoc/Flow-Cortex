package com.flowcortex.workflow.application;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The pool batch dispatch passes run on, apart from the two-thread scheduler that heartbeats and the lease reaper
 * share, so a long pass never delays them. Wrapped like the other module pools: a bare executor bean would make
 * Boot drop its shared application executor.
 */
@Configuration(proxyBeanMethods = false)
class BatchDispatchConfig {

    static final int THREADS = 2;
    static final int QUEUE = 50;

    @Bean
    BatchDispatchExecutor batchDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("batch-dispatch-");
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE);
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return new BatchDispatchExecutor(executor);
    }

    /** Hands tasks to the executor and shuts it down with the context. */
    record BatchDispatchExecutor(ThreadPoolTaskExecutor executor) implements DisposableBean {

        /** @throws org.springframework.core.task.TaskRejectedException when both threads and the queue are full */
        void execute(Runnable task) {
            executor.execute(task);
        }

        @Override
        public void destroy() {
            executor.shutdown();
        }
    }
}
