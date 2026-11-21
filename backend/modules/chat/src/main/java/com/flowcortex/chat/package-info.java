/**
 * Chat module: the member's main chat, its conversations and messages.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Chat",
        allowedDependencies = {
                "user :: api",
                "llm :: api",
                "workflow :: api",
                "media :: api",
                "realtime :: api",
                "shared :: web",
                "shared :: security",
                "shared :: ratelimit"
        }
)
package com.flowcortex.chat;
