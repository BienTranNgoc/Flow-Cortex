package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One version without its snapshot. {@code source} is {@code manual}, {@code publish}, {@code rollback} or
 * {@code hotfix}; {@code published} is true for the version the workflow's pointer names; {@code releasedAt} is null
 * until the pointer has first landed on the version.
 */
public record WorkflowVersionView(UUID id, int version, String changelog, String source, Integer restoredFromVersion,
        UUID publishedBy, Instant publishedAt, Instant releasedAt, boolean published) {
}
