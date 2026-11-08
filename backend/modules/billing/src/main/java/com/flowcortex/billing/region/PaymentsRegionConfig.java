package com.flowcortex.billing.region;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsRegionProperties.class)
class PaymentsRegionConfig {
}
