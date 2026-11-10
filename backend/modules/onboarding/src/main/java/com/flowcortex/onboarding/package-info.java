/**
 * Onboarding module: a member's first-run quiz answers, journey progress and builder state.
 * <p>
 * This is a Spring Modulith application module. It reads users and run history only through
 * their public {@code api} ports, plus the shared web and security libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Onboarding",
        allowedDependencies = {
                "user :: api",
                "workflow :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.onboarding;
