/**
 * Workflow module: workflow definitions and the run engine.
 * <p>
 * This is a Spring Modulith application module. Inter-module dependencies are
 * declared explicitly below: it depends on the user module only through its
 * public {@code api} port (for the current user), on the media module only through
 * its {@code api} port (to dispatch remote steps and receive their results), plus the
 * shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Workflow",
        allowedDependencies = {
                "user :: api",
                "media :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.workflow;
