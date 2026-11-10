package com.flowcortex.workflow.application.provider;

import com.flowcortex.llm.api.LlmSlots;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DubProperties.class)
class DubConfig {

    /** Stops boot, naming the slot, when a slot the dub steps call is not configured. */
    @Bean
    InitializingBean dubSlotsPresent(LlmSlots llm) {
        return () -> {
            llm.jsonWorstCase(DubSlots.TRANSLATE);
            llm.speechWorstCase(DubSlots.SPEECH);
        };
    }

    @Bean
    ProviderStepPool providerStepPool(DubProperties dub) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("dub-step-");
        executor.setCorePoolSize(dub.pool().size());
        executor.setMaxPoolSize(dub.pool().size());
        executor.setQueueCapacity(dub.pool().queueCapacity());
        // The trace and the run's baggage follow the task, as on the shared pool.
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return new OwnedPool(executor);
    }

    /** Hands tasks to the executor and shuts it down with the context. */
    private record OwnedPool(ThreadPoolTaskExecutor executor) implements ProviderStepPool, DisposableBean {

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
