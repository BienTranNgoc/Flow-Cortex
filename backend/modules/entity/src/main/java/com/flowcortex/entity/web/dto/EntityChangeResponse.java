package com.flowcortex.entity.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowcortex.entity.domain.EntityChanges.Change;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * One thing a version changed. {@code added} and {@code removed} (tag labels) come with {@code tagsChanged} and
 * {@code from} (a version number) with {@code restored}; every other part is absent.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EntityChangeResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"created", "renamed", "handleChanged",
                "fieldsChanged", "partsChanged", "tagsChanged", "categoryChanged", "restored"}) String code,
        List<String> added,
        List<String> removed,
        Integer from) {

    static EntityChangeResponse from(Change change) {
        return new EntityChangeResponse(change.code(), change.added(), change.removed(), change.from());
    }
}
