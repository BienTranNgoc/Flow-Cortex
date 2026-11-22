package com.flowcortex.drive.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of Memory with the facets the screen shows beside it. */
record MemoryListResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<MemoryEntryResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int pageSize,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<MemoryEntryResponse> pinned,
        @Schema(requiredMode = RequiredMode.REQUIRED) SizeBuckets sizeBuckets) {

    /** Matches per size bucket under every filter except the size one. */
    record SizeBuckets(
            @Schema(requiredMode = RequiredMode.REQUIRED) long lt1mb,
            @JsonProperty("1to50mb") @Schema(name = "1to50mb", requiredMode = RequiredMode.REQUIRED)
            long between1and50mb,
            @Schema(requiredMode = RequiredMode.REQUIRED) long gt50mb) {
    }
}
