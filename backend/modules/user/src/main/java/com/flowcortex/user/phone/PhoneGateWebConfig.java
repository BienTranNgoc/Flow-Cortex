package com.flowcortex.user.phone;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declared as a bean, not scanned, so a controller slice test does not pull the interceptor in without its gate. */
@Configuration(proxyBeanMethods = false)
class PhoneGateWebConfig {

    @Bean
    PhoneGateInterceptor phoneGateInterceptor(PhoneGate gate, PhoneTelemetry telemetry) {
        return new PhoneGateInterceptor(gate, telemetry);
    }
}
