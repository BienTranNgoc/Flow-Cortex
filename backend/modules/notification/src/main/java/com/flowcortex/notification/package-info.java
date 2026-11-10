/**
 * Notification module: the in-app inbox other modules raise notifications into, with its read
 * routes and live signal.
 * <p>
 * This is a Spring Modulith application module. A producer publishes
 * {@code com.flowcortex.notification.api.NotificationRequested}; nothing else of it is public.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {
                "user :: api",
                "realtime :: api",
                "mail :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.notification;
