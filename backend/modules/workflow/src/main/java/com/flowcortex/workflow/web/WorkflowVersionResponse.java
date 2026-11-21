package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowVersionView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** {@code releasedAt} is null until the published pointer has first landed on the version. */
record WorkflowVersionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) int version,
        @Schema(nullable = true) String changelog,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"manual", "publish", "rollback", "hotfix"})
        String source,
        @Schema(nullable = true) Integer restoredFromVersion,
        @Schema(nullable = true) UUID publishedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant publishedAt,
        @Schema(nullable = true) Instant releasedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean published) {

    static WorkflowVersionResponse from(WorkflowVersionView view) {
        return new WorkflowVersionResponse(view.id(), view.version(), view.changelog(), view.source(),
                view.restoredFromVersion(), view.publishedBy(), view.publishedAt(), view.releasedAt(),
                view.published());
    }
}
