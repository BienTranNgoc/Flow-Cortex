package com.flowcortex.trash.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/** One page of the member's Trash with the badge counts of every provided type. */
@Schema(name = "TrashList")
public record TrashListView(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<TrashItemView> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Entries matching type and q") long total,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Entries per provided type and under 'all', whatever type, q and paging say")
        Map<String, Long> counts) {
}
