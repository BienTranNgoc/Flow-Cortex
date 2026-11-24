package com.flowcortex.billing.catalog;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The check is declared here so it reads the tiers through the reader and tests can give it a lambda instead. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogueCheckProperties.class)
class CatalogueCheckConfig {

    @Bean
    CatalogueCheck catalogueCheck(CatalogueReader reader, CatalogueTelemetry telemetry,
            CatalogueCheckProperties properties) {
        return new CatalogueCheck(reader::readOrderableTiers, telemetry, properties);
    }
}
