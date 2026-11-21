/**
 * Billing module: payment-side policy. It holds the payments region gate that refuses to open a payment
 * for a caller whose address is registered outside Vietnam, the payment reference formats and provider
 * event, and the endpoint that records what a payer's browser saw.
 * <p>
 * This is a Spring Modulith application module. It depends only on the shared web and rate-limit libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {
                "shared :: web",
                "shared :: ratelimit"
        }
)
package com.flowcortex.billing;
