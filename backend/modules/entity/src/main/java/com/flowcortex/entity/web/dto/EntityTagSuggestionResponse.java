package com.flowcortex.entity.web.dto;

import com.flowcortex.entity.application.EntitySuggestionService.Counts;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** Tags the space already uses, with how many live entities carry each. */
public record EntityTagSuggestionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<EntityTagSuggestionItem> items) {

    /** One suggested tag and its use count. */
    public record EntityTagSuggestionItem(
            @Schema(requiredMode = RequiredMode.REQUIRED) String tag,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    public static EntityTagSuggestionResponse from(List<Counts> counts) {
        return new EntityTagSuggestionResponse(
                counts.stream().map(c -> new EntityTagSuggestionItem(c.label(), c.count())).toList());
    }
}
