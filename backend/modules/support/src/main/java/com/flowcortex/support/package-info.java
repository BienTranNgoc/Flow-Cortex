/**
 * Support module: the Help Center's knowledge base, assistant and support requests.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Support",
        allowedDependencies = {
                "user :: api",
                "media :: api",
                "llm :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.support;
