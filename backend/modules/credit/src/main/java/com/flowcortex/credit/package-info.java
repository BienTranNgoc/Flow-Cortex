/**
 * Credit module: per-user credit wallets, the credit ledger, and the per-step costs
 * charged for workflow runs.
 * <p>
 * This is a Spring Modulith application module. It depends on the user module only
 * through its public {@code api} port, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Credit",
        allowedDependencies = {
                "user :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.credit;
