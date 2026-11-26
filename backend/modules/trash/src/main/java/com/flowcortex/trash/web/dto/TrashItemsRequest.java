package com.flowcortex.trash.web.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** The items a restore or purge acts on: 1 to 100 distinct {@code {type, id}} pairs. */
@Schema(name = "TrashItemsRequest")
public record TrashItemsRequest(
        @ArraySchema(minItems = 1, maxItems = 100, arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
        List<Item> items) {

    /** One trashed item, named by its type key and its id in the owning module. */
    @Schema(name = "TrashItemKey")
    public record Item(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[a-z][a-z0-9_]{1,31}$") String type,
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Za-z0-9_-]{1,64}$") String id) {
    }
}
