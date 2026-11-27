package com.flowcortex.user.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a member's activity timeline. {@code id} is the id of the source row; {@code actorType} is
 * {@code admin} or {@code user}; {@code actorLabel}, the object fields and {@code status} are {@code null}
 * where the source has none.
 */
public record UserActivityItem(
        UUID id,
        String source,
        String action,
        String actorType,
        String actorLabel,
        String objectType,
        String objectId,
        String objectLabel,
        String status,
        Instant occurredAt) {

    public static final String ACTOR_ADMIN = "admin";
    public static final String ACTOR_USER = "user";
}
