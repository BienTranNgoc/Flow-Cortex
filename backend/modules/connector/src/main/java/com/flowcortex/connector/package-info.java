/**
 * Connector module: per-member credentials for third-party providers, stored as ciphertext.
 * <p>
 * This is a Spring Modulith application module. It depends on no other module: callers pass the
 * member id, and the only way in is the {@code api} port.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Connector",
        allowedDependencies = {}
)
package com.flowcortex.connector;
