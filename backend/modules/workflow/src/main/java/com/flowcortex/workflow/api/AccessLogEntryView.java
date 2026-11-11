package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One read through the share key. {@code userId} and {@code name} are {@code null} for an anonymous reader;
 * {@code network} is the masked address.
 */
public record AccessLogEntryView(UUID id, Instant accessedAt, UUID userId, String name, String network,
        String userAgent) {
}
