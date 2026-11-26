package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.infrastructure.EntityRunStore.CompletedRun;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** A completed run that used the entity, at the entity version it was recorded with. */
public record EntityRunResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
        UUID workflowId,
        String workflowName,
        @Schema(requiredMode = RequiredMode.REQUIRED) int version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant completedAt) {

    public static EntityRunResponse from(CompletedRun run) {
        return new EntityRunResponse(run.runId(), run.workflowId(), run.workflowName(), run.version(),
                run.completedAt());
    }
}
