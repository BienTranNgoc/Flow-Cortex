/**
 * Trend module: the category and tag catalogue behind trend templates, its admin API, and the
 * purge job that removes deleted templates once a grace period has passed.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Trend",
        allowedDependencies = {
                "jobs :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.trend;
