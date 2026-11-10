package com.flowcortex.billing.region;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The interceptor is declared here rather than scanned, so web-slice tests never pull it in without its service. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsRegionProperties.class)
class PaymentsRegionConfig {

    @Bean
    PaymentsRegionInterceptor paymentsRegionInterceptor(PaymentsRegionService service,
            PaymentsRegionProperties properties, RegionTelemetry telemetry) {
        return new PaymentsRegionInterceptor(service, properties, telemetry);
    }
}
