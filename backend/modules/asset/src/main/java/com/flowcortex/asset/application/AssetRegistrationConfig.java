package com.flowcortex.asset.application;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssetRegistrationConfig.PoolProperties.class)
class AssetRegistrationConfig {

    /** {@code size} registrations run at once and {@code queueCapacity} wait; one arriving with both full is rejected. */
    @Validated
    @ConfigurationProperties("flowcortex.asset.registration.pool")
    record PoolProperties(@DefaultValue("2") @Positive int size,
            @DefaultValue("20") @PositiveOrZero int queueCapacity) {
    }

    @Bean
    AssetRegistrationPool assetRegistrationPool(PoolProperties pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("asset-registration-");
        executor.setCorePoolSize(pool.size());
        executor.setMaxPoolSize(pool.size());
        executor.setQueueCapacity(pool.queueCapacity());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return new OwnedPool(executor);
    }

    /** Hands tasks to the executor and shuts it down with the context. */
    private record OwnedPool(ThreadPoolTaskExecutor executor) implements AssetRegistrationPool, DisposableBean {

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
