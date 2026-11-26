package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntitySuggestionService.Counts;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** Categories or part labels the space already uses, with how many live entities (or parts) carry each. */
public record EntitySuggestionResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<EntitySuggestionItem> items) {

    /** One suggested text and its use count. */
    public record EntitySuggestionItem(
            @Schema(requiredMode = RequiredMode.REQUIRED) String label,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    public static EntitySuggestionResponse from(List<Counts> counts) {
        return new EntitySuggestionResponse(
                counts.stream().map(c -> new EntitySuggestionItem(c.label(), c.count())).toList());
    }
}
