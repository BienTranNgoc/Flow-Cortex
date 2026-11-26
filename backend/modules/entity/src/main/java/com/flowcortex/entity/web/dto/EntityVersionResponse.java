package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityVersionService.VersionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;

/** A version in the list: what it changed and how many completed runs used it. */
public record EntityVersionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) int version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean current,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<EntityChangeResponse> changes,
        @Schema(requiredMode = RequiredMode.REQUIRED) int usedInRuns) {

    public static EntityVersionResponse from(VersionItem item) {
        return new EntityVersionResponse(item.version(), item.createdAt(), item.current(),
                item.changes().stream().map(EntityChangeResponse::from).toList(), item.usedInRuns());
    }
}
