package com.flowcortex.user.accountdeletion;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declared as a bean, not scanned, so a controller slice test does not pull the interceptor in without its gate. */
@Configuration(proxyBeanMethods = false)
class AccountDeletionGateWebConfig {

    @Bean
    AccountDeletionGateInterceptor accountDeletionGateInterceptor(AccountDeletionGate gate, MeterRegistry meters) {
        return new AccountDeletionGateInterceptor(gate, meters);
    }
}
