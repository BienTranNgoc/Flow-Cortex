/**
 * Settings module: runtime settings declared by feature modules, one stored value per key with a
 * history of its changes.
 * <p>
 * This is a Spring Modulith application module. It depends only on the shared libraries, so every
 * feature module can declare keys through its {@code api} port.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Settings",
        allowedDependencies = {
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.settings;
