/**
 * Entity module: the brands, products, characters and voices a member keeps in their space, with
 * their parts, tags, category, version history and the runs that used them.
 * <p>
 * This is a Spring Modulith application module. It reaches the user, space, asset and workflow
 * modules only through their public {@code api} ports, plus the shared web library.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Entity",
        allowedDependencies = {
                "user :: api",
                "space :: api",
                "asset :: api",
                "workflow :: api",
                "shared :: web"
        }
)
package com.flowcortex.entity;
