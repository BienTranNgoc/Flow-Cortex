package com.flowcortex.workflow.application.provider;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DubProperties.class)
class DubConfig {

    @Bean
    GeminiClient geminiClient(DubProperties dub, ObjectProvider<ObservationRegistry> observations) {
        return GeminiClient.create(dub.gemini(), wait -> Thread.sleep(wait),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
