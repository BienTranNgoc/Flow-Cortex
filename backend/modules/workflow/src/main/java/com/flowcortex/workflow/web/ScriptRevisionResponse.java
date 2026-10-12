package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ScriptRevisionView;
import java.time.Instant;
import java.util.UUID;

record ScriptRevisionResponse(UUID id, UUID runId, UUID reviewTaskId, int revisionNumber,
        String content, UUID createdBy, Instant createdAt, Long reviewTaskVersion) {
    static ScriptRevisionResponse from(ScriptRevisionView view) {
        return new ScriptRevisionResponse(view.id(), view.runId(), view.reviewTaskId(), view.revisionNumber(),
                view.content(), view.createdBy(), view.createdAt(), view.reviewTaskVersion());
    }
}
