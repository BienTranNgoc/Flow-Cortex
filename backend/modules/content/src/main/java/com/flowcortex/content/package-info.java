/**
 * Content module: staff-edited, versioned documents (draft, published copy, append-only history)
 * and the content media library.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Content",
        allowedDependencies = {
                "user :: api",
                "media :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.content;
