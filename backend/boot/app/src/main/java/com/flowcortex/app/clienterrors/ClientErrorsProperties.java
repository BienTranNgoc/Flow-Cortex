package com.flowcortex.app.clienterrors;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code flowcortex.client-errors.*}; read at startup.
 *
 * @param enabled whether the intake reads, limits and records reports; off answers 204 to everything
 * @param perIp   reports one client address may send per period
 */
@ConfigurationProperties("flowcortex.client-errors")
record ClientErrorsProperties(@DefaultValue("true") boolean enabled, @DefaultValue PerIp perIp) {

    record PerIp(@DefaultValue("10") int capacity, @DefaultValue("1m") Duration period) {
    }
}
