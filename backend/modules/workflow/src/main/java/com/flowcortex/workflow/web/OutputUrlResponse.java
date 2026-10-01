package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunOutputUrlView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** GET {@code url} without an {@code Authorization} header; the store answers it as an attachment until {@code expiresAt}. */
record OutputUrlResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {

    static OutputUrlResponse from(RunOutputUrlView view) {
        return new OutputUrlResponse(view.url(), view.expiresAt());
    }

    @Override
    public String toString() {
        return "OutputUrlResponse[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
