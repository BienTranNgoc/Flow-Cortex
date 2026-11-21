package com.flowcortex.billing.region;

import com.flowcortex.settings.api.Settings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The interceptor is declared here rather than scanned, so web-slice tests never pull it in without its service. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsRegionProperties.class)
class PaymentsRegionConfig {

    @Bean
    PaymentsRegionModeSource paymentsRegionModeSource(Settings settings) {
        return new PaymentsRegionModeSource(settings);
    }

    @Bean
    PaymentsRegionInterceptor paymentsRegionInterceptor(PaymentsRegionService service,
            PaymentsRegionModeSource modeSource, RegionTelemetry telemetry) {
        return new PaymentsRegionInterceptor(service, modeSource, telemetry);
    }
}
