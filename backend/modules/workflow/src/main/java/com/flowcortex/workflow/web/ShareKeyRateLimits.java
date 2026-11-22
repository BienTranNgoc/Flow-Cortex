package com.flowcortex.workflow.web;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The anonymous share-key route's budget per client address, shared by every backend instance. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShareKeyRateLimitProperties.class)
class ShareKeyRateLimits {

    static final String SHARE_KEY = "workflow-share-key";

    @Bean
    RateLimitDefinition workflowShareKeyLimit(ShareKeyRateLimitProperties properties) {
        return new RateLimitDefinition(SHARE_KEY, properties.capacity(), properties.period());
    }
}
