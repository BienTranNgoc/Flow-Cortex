package com.flowcortex.user.authz;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declared as a bean, not scanned, so a controller slice test does not pull the interceptor in without its loader. */
@Configuration(proxyBeanMethods = false)
class AuthzWebConfig {

    @Bean
    AdminAuthorizationInterceptor adminAuthorizationInterceptor(CallerFactsLoader factsLoader) {
        return new AdminAuthorizationInterceptor(factsLoader);
    }
}
