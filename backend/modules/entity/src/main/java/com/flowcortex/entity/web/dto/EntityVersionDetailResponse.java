package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityVersionService.VersionDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/**
 * One version read as an entity: the stored content under that version's number, {@code updatedAt} being the time
 * the version was written. Part ids are derived, not the ids of live parts.
 */
public record EntityVersionDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) int version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean current,
        @Schema(requiredMode = RequiredMode.REQUIRED) EntityResponse entity) {

    public static EntityVersionDetailResponse from(VersionDetail detail) {
        return new EntityVersionDetailResponse(detail.version(), detail.createdAt(), detail.current(),
                EntityResponse.from(detail.entity()));
    }
}
