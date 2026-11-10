/**
 * Profile module: a member's avatar and cover pictures, their history of uploaded originals and the
 * current image of each slot.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Profile",
        allowedDependencies = {
                "user :: api",
                "media :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.profile;
