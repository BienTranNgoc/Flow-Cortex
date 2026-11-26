package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityVersionService.DroppedPart;
import com.flowcortex.entity.application.EntityVersionService.RestoreResult;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The entity after a restore, in the entity shape, with the parts of the restored version that could not come back. */
public record EntityRestoreResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"brand", "product", "character", "voice"})
        String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String handle,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, Object> fields,
        @Schema(nullable = true) String categoryLabel,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<String> tags,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<EntityPartResponse> parts,
        @Schema(requiredMode = RequiredMode.REQUIRED) int version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<EntityDroppedPart> droppedParts) {

    /** A part of the restored version whose asset is no longer in the library. */
    public record EntityDroppedPart(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID assetId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String role,
            String label) {

        static EntityDroppedPart from(DroppedPart part) {
            return new EntityDroppedPart(part.assetId(), part.role(), part.label());
        }
    }

    public static EntityRestoreResponse from(RestoreResult result) {
        EntityResponse entity = EntityResponse.from(result.entity());
        return new EntityRestoreResponse(entity.id(), entity.kind(), entity.handle(), entity.name(), entity.fields(),
                entity.categoryLabel(), entity.tags(), entity.parts(), entity.version(), entity.createdAt(),
                entity.updatedAt(), result.droppedParts().stream().map(EntityDroppedPart::from).toList());
    }
}
