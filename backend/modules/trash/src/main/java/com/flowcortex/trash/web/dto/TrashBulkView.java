package com.flowcortex.trash.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** What a restore or purge did to each item, both lists in request order. */
@Schema(name = "TrashBulkResult")
public record TrashBulkView(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<TrashItemsRequest.Item> succeeded,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Skipped> skipped) {

    /**
     * An item that was left in the Trash. {@code message} is the owning module's reason in the reader's language,
     * present for {@code conflict} and {@code blocked}.
     */
    @Schema(name = "TrashSkippedItem")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skipped(
            @Schema(requiredMode = RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"notFound", "conflict", "blocked", "failed"}) String reason,
            String message) {
    }
}
