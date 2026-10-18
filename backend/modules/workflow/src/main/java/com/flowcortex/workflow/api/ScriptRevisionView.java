package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

public record ScriptRevisionView(UUID id, UUID runId, UUID reviewTaskId, int revisionNumber,
        String content, UUID createdBy, Instant createdAt, Long reviewTaskVersion) {
}
