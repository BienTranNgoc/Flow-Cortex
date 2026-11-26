package com.flowcortex.billing.entitlement;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The check is declared here so it reads the catalogue through the repository and tests can give it a lambda. */
@Configuration(proxyBeanMethods = false)
class PlanPeriodCheckConfig {

    @Bean
    PlanPeriodCheck planPeriodCheck(EntitlementRepository repository, EntitlementTelemetry telemetry) {
        return new PlanPeriodCheck(repository::soldCycles, telemetry);
    }
}
