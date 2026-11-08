/**
 * Space module: the personal space every member account owns, its memberships, and the port
 * later modules call to scope their data to a space.
 * <p>
 * This is a Spring Modulith application module. It depends on the user module only through its
 * public {@code api} port, plus the shared web library.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Space",
        allowedDependencies = {
                "user :: api",
                "shared :: web"
        }
)
package com.flowcortex.space;
