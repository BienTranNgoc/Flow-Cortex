/**
 * Realtime module: the authenticated SSE stream and the fanout of events between instances.
 * Other modules publish through its {@code api} port and authorize their topics via
 * {@code TopicAuthorizer}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Realtime",
        allowedDependencies = {
                "user :: api",
                "shared :: web",
                "shared :: security"
        }
)
package com.flowcortex.realtime;
