package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntityService.NewEntityInput;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/**
 * Create body. {@code handle} is generated from the name when absent; {@code fields}, {@code parts} and
 * {@code tags} start empty. Field-level rules answer with {@code errors.<field>}.
 */
public record CreateEntityRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"brand", "product", "character", "voice"})
        String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        String handle,
        Map<String, Object> fields,
        List<EntityPartRequest> parts,
        List<String> tags,
        String categoryLabel) {

    public NewEntityInput toInput() {
        return new NewEntityInput(kind, name, handle, fields, EntityPartRequest.toInputs(parts), tags, categoryLabel);
    }
}
