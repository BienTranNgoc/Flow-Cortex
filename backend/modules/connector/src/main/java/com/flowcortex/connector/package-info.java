/**
 * Connector module: per-member credentials for third-party providers, stored as ciphertext, and the
 * shared gate and outbound client every connector uses.
 * <p>
 * This is a Spring Modulith application module. It resolves the signed-in member through the user
 * module's {@code api} port only; other modules reach it through its own {@code api} port.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Connector",
        allowedDependencies = {
                "user :: api",
                "shared :: web"
        }
)
package com.flowcortex.connector;
