package com.flowcortex.realtime.api;

import java.util.UUID;

/**
 * Decides who may read a family of topics. A topic is valid only when some authorizer claims
 * it, so a module adds a topic by registering one of these and the hub needs no change.
 */
public interface TopicAuthorizer {

    /** Whether this authorizer owns {@code topic}; an unclaimed topic is a malformed request. */
    boolean supports(String topic);

    /** Whether {@code userId} may read the claimed {@code topic}. */
    boolean authorize(UUID userId, String topic);
}
