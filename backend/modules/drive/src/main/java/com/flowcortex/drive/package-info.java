/**
 * Drive module: the items a member keeps in their personal space and the Memory of their
 * completed runs.
 * <p>
 * This is a Spring Modulith application module. It reaches the user, space, media and workflow
 * modules only through their public {@code api} ports, plus the shared web library.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Drive",
        allowedDependencies = {
                "user :: api",
                "space :: api",
                "media :: api",
                "workflow :: api",
                "shared :: web"
        }
)
package com.flowcortex.drive;
