package com.flowcortex.billing.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.catalogue.check.*}: whether the tier-order check runs, and the wait between the
 * end of one daily pass and the start of the next. Read once at startup.
 */
@ConfigurationProperties("flowcortex.billing.catalogue.check")
public record CatalogueCheckProperties(Boolean enabled, Duration interval) {

    public CatalogueCheckProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        interval = interval == null ? Duration.ofHours(24) : interval;
    }
}
