package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityService.EntityPatch;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/**
 * Edit body. An absent field stays as it is; {@code fields}, {@code parts} and {@code tags} replace the whole value.
 * {@code categoryLabel} is cleared by an explicit {@code null}. {@code kind} never changes: it is accepted only to
 * answer a different one with {@code errors.kind}.
 */
public record UpdateEntityRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED) Integer baseVersion,
        String kind,
        String name,
        String handle,
        Map<String, Object> fields,
        List<EntityPartRequest> parts,
        List<String> tags,
        @Schema(nullable = true, type = "string") CategoryEdit categoryLabel) {

    public EntityPatch toPatch() {
        return new EntityPatch(baseVersion, kind, name, handle, fields, EntityPartRequest.toInputs(parts), tags,
                categoryLabel != null, categoryLabel == null ? null : categoryLabel.label());
    }
}
