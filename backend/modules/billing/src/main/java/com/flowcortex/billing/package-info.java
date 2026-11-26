/**
 * Billing module: payment-side policy. It holds the payments region gate that refuses to open a payment
 * for a caller whose address is registered outside Vietnam, the payment reference formats and provider
 * event, the endpoint that records what a payer's browser saw, and the payment settings console. It reads
 * which publication of the payment terms was in force through {@code content :: api}, and mails each
 * collected payment's receipt through {@code mail :: api} from a cluster job of {@code jobs :: api}.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {
                "content :: api",
                "jobs :: api",
                "mail :: api",
                "settings :: api",
                "user :: api",
                "shared :: web",
                "shared :: security",
                "shared :: ratelimit"
        }
)
package com.flowcortex.billing;
