package com.flowcortex.connector.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A member's connection to one provider, as callers may see it: no token of any kind. */
public record ConnectorConnection(
        String provider,
        List<String> scopes,
        Map<String, Object> metadata,
        Instant expiresAt,
        boolean active,
        boolean usageEnabled,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt) {
}
