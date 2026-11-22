package com.flowcortex.workflow.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code flowcortex.workflow.share.rate-limit.*}; read at startup.
 *
 * @param capacity anonymous share-key reads one client address may make per period
 * @param period   the span the budget refills over
 */
@ConfigurationProperties("flowcortex.workflow.share.rate-limit")
record ShareKeyRateLimitProperties(@DefaultValue("30") int capacity, @DefaultValue("1m") Duration period) {
}
