package com.flowcortex.app.observability;

import jakarta.servlet.Filter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Modulith's module observability wraps each bean a module exposes in a class-based proxy.
 * A servlet filter's {@code init} is final, so it would run on the proxy, whose fields are unset,
 * and the web server fails to start. Filters are therefore declared infrastructure, which the
 * module observability leaves alone; the server observation covers every request anyway.
 */
@Configuration(proxyBeanMethods = false)
class ModuleObservabilityConfig {

    @Bean
    static BeanFactoryPostProcessor servletFiltersAreInfrastructure() {
        return factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type != null && Filter.class.isAssignableFrom(type)) {
                    factory.getBeanDefinition(name).setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
                }
            }
        };
    }
}
