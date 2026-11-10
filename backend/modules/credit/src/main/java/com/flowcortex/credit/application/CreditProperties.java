package com.flowcortex.credit.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code flowcortex.credits.*}. Off: no balance check at start, no charge or refund, and
 * the balance endpoint answers 503. Prices come from {@code rate_card} whatever the switch says.
 */
@ConfigurationProperties("flowcortex.credits")
record CreditProperties(boolean enabled) {
}
