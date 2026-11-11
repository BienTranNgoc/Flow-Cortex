/**
 * Asset module: the library of assets a member's runs produce and consume, and the durable queue
 * that registers them.
 * <p>
 * This is a Spring Modulith application module. It reads other modules only through their public
 * {@code api} ports; nothing depends on it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Asset",
        allowedDependencies = {
                "workflow :: api",
                "media :: api",
                "space :: api",
                "jobs :: api",
                "shared :: web"
        }
)
package com.flowcortex.asset;
