package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An entity of the caller's space; {@code categoryLabel} is absent when it has no category. */
public record EntityResponse(
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
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

    public static EntityResponse from(EntityView view) {
        return new EntityResponse(view.id(), view.kind(), view.handle(), view.name(), view.fields(),
                view.categoryLabel(), view.tags(), view.parts().stream().map(EntityPartResponse::from).toList(),
                view.version(), view.createdAt(), view.updatedAt());
    }
}
