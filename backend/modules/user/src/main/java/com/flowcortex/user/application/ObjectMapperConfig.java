package com.flowcortex.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Provides a module-specific {@link ObjectMapper} for {@link DefaultAuditEventWriter}.
 * Spring Boot's auto-configured one is always present, so the bean name must differ to
 * avoid {@code BeanDefinitionOverrideException}; {@code @Primary} keeps autowire-by-type
 * on {@code DefaultAuditEventWriter} working without an explicit {@code @Qualifier}.
 */
@Configuration(proxyBeanMethods = false)
public class ObjectMapperConfig {

    @Bean
    @Primary
    ObjectMapper userModuleObjectMapper() {
        return new ObjectMapper();
    }
}
