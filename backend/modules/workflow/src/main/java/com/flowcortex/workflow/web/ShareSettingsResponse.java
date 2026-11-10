package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ShareSettingsView;
import com.flowcortex.workflow.api.WorkflowVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** {@code shareKey} and its timestamps are null unless the visibility is {@code unlisted}. */
record ShareSettingsResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) WorkflowVisibility visibility,
        @Schema(nullable = true) String shareKey, @Schema(nullable = true) Instant keyGeneratedAt,
        @Schema(nullable = true) Instant keyExpiresAt) {

    static ShareSettingsResponse from(ShareSettingsView view) {
        return new ShareSettingsResponse(view.visibility(), view.shareKey(), view.keyGeneratedAt(),
                view.keyExpiresAt());
    }
}
