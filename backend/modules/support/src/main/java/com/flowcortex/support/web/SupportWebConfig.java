package com.flowcortex.support.web;

import com.flowcortex.support.config.SupportProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Declared as a bean, not scanned, so a controller slice test does not pull the filter in without its properties. */
@Configuration(proxyBeanMethods = false)
class SupportWebConfig {

    @Bean
    SupportSwitchFilter supportSwitchFilter(SupportProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return new SupportSwitchFilter(properties, exceptionResolver);
    }
}
