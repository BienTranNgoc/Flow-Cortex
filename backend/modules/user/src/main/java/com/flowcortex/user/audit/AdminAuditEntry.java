package com.flowcortex.user.audit;

import java.util.Locale;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One admin write to record; {@code changeSummary} is the already-reduced snapshot or null. */
public record AdminAuditEntry(
        UUID actorUserId,
        ActorSnapshot actor,
        String action,
        String resourceType,
        String resourceId,
        String resourceLabel,
        JsonNode changeSummary,
        Status status,
        String errorCode,
        String ip,
        String userAgent) {

    public enum Status {
        SUCCESS,
        FAILED;

        String column() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
