package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.VersionPinView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** The caller's pin on a workflow; every field is null when there is none. */
record VersionPinResponse(
        @Schema(nullable = true) Integer pinnedVersion,
        @Schema(nullable = true) String reason,
        @Schema(nullable = true) Instant pinnedAt) {

    static VersionPinResponse from(VersionPinView view) {
        return new VersionPinResponse(view.pinnedVersion(), view.reason(), view.pinnedAt());
    }
}
