package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityService.EntityPage;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/** One page of entities. {@code counts} holds the matches of each kind under every filter except {@code kind}. */
public record EntityListResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<EntityResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = RequiredMode.REQUIRED) EntityKindCounts counts) {

    /** Matching entities per kind. */
    public record EntityKindCounts(
            @Schema(requiredMode = RequiredMode.REQUIRED) long brand,
            @Schema(requiredMode = RequiredMode.REQUIRED) long product,
            @Schema(requiredMode = RequiredMode.REQUIRED) long character,
            @Schema(requiredMode = RequiredMode.REQUIRED) long voice) {

        static EntityKindCounts from(Map<String, Long> byKind) {
            return new EntityKindCounts(byKind.get("brand"), byKind.get("product"), byKind.get("character"),
                    byKind.get("voice"));
        }
    }

    public static EntityListResponse from(EntityPage result) {
        return new EntityListResponse(result.page().items().stream().map(EntityResponse::from).toList(),
                result.page().page(), result.page().size(), result.page().total(),
                EntityKindCounts.from(result.counts()));
    }
}
