/**
 * Billing module: payment-side policy, starting with the payments region gate that refuses to open
 * a payment for a caller whose address is registered outside Vietnam.
 * <p>
 * This is a Spring Modulith application module. It depends only on the shared web library.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {
                "shared :: web"
        }
)
package com.flowcortex.billing;
