package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.domain.PartInput;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/** A part of an entity: one of the caller's library assets in a role of the entity's kind. */
public record EntityPartRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID assetId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String role,
        String label) {

    /** The parts as the rules take them; an absent list stays {@code null}. */
    public static List<PartInput> toInputs(List<EntityPartRequest> parts) {
        if (parts == null) {
            return null;
        }
        return parts.stream().map(part -> part == null ? null : new PartInput(part.assetId(), part.role(), part.label()))
                .toList();
    }
}
