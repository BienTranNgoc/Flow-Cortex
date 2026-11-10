package com.flowcortex.workflow.api;

import java.time.Instant;

/**
 * A workflow's sharing state as its owner sees it. {@code shareKey} and its timestamps are set only while the
 * visibility is {@code unlisted}; {@code keyExpiresAt} is {@code null} for a key that never expires.
 */
public record ShareSettingsView(WorkflowVisibility visibility, String shareKey, Instant keyGeneratedAt,
        Instant keyExpiresAt) {
}
