/**
 * Trash module: the member's index of soft-deleted items across every owning module, with its list,
 * restore, purge and empty routes and the expiry sweep and reminder.
 * <p>
 * This is a Spring Modulith application module. An owning module records and drops entries through
 * the {@code api} port and plugs its type in by implementing the provider there; the Trash depends
 * on none of them.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Trash",
        allowedDependencies = {
                "user :: api",
                "jobs :: api",
                "notification :: api",
                "mail :: api",
                "shared :: web"
        }
)
package com.flowcortex.trash;
