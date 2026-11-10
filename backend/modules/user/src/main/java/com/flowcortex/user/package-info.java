/**
 * User module: manages application users, authentication, and the
 * current-user port other modules depend on.
 * <p>
 * This is a Spring Modulith application module. Inter-module dependencies are
 * declared explicitly below: only the jobs {@code api} port (its retention jobs run on the cluster
 * runner) and the shared libraries (shared :: web, shared :: security) may be used from outside
 * this module's own packages.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "User",
        allowedDependencies = {
                "jobs :: api",
                "shared :: web",
                "shared :: security",
                "settings :: api"
        }
)
package com.flowcortex.user;
