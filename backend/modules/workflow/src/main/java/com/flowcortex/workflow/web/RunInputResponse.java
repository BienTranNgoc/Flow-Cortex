package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunInputView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** GET {@code url} without an {@code Authorization} header until {@code expiresAt}. */
record RunInputResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String bindingKey,
        @Schema(requiredMode = RequiredMode.REQUIRED) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String mimeType,
        @Schema(requiredMode = RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {

    static RunInputResponse from(RunInputView view) {
        return new RunInputResponse(view.bindingKey(), view.kind(), view.mimeType(), view.url(), view.expiresAt());
    }

    @Override
    public String toString() {
        return "RunInputResponse[bindingKey=" + bindingKey + ", kind=" + kind + ", mimeType=" + mimeType
                + ", url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
