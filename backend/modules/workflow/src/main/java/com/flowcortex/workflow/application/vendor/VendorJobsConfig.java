package com.flowcortex.workflow.application.vendor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link VendorJobsProperties}; without it the record is declared but never bound. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VendorJobsProperties.class)
class VendorJobsConfig {
}
