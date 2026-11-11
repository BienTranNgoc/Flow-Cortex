package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One visibility change or key rotation. {@code triggeredBy} is {@code owner}, {@code admin} or
 * {@code key_regenerated}; {@code name} is {@code null} when the account no longer exists.
 */
public record VisibilityAuditEntryView(UUID id, Instant changedAt, UUID changedBy, String name,
        WorkflowVisibility fromVisibility, WorkflowVisibility toVisibility, String triggeredBy, String network,
        String userAgent) {
}
