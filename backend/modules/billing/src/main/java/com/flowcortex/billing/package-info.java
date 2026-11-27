/**
 * Billing module: payment-side policy. It holds the payments region gate that refuses to open a payment
 * for a caller whose address is registered outside Vietnam, the payment reference formats and provider
 * event, the endpoint that records what a payer's browser saw, the payment settings console, and the ledger
 * and reconciliation that keep every payment's money status true to the provider's, announcing each move
 * through its public {@code api} event.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {
                "jobs :: api",
                "settings :: api",
                "user :: api",
                "shared :: web",
                "shared :: security",
                "shared :: ratelimit"
        }
)
package com.flowcortex.billing;
