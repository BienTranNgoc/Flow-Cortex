/**
 * Billing module: payment-side policy, starting with the payments region gate that refuses to open
 * a payment for a caller whose address is registered outside Vietnam.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {
                "settings :: api",
                "user :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.billing;
