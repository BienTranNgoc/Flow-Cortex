package com.flowcortex.trash.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The optional body of an empty call: where the previous call stopped. */
@Schema(name = "TrashEmptyRequest")
public record TrashEmptyRequest(
        @Schema(nullable = true, description = "The next of the previous answer; absent starts from the first entry")
        String next) {
}
