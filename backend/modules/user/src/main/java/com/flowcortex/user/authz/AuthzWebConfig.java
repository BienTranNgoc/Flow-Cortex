package com.flowcortex.user.authz;

import com.flowcortex.user.totp.TotpRequirement;
import com.flowcortex.user.totp.TotpService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declared as a bean, not scanned, so a controller slice test does not pull the interceptor in without its loader. */
@Configuration(proxyBeanMethods = false)
class AuthzWebConfig {

    @Bean
    AdminAuthorizationInterceptor adminAuthorizationInterceptor(CallerFactsLoader factsLoader,
            ObjectProvider<TotpRequirement> requirement, ObjectProvider<TotpService> totp) {
        return new AdminAuthorizationInterceptor(factsLoader, requirement, totp);
    }
}
