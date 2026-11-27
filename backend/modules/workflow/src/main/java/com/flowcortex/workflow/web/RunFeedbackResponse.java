package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunFeedbackView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** The stored verdict; {@code issue} is null for {@code up} and {@code detail} is null when none was given. */
record RunFeedbackResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String verdict,
        String issue,
        String detail,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

    static RunFeedbackResponse from(RunFeedbackView view) {
        return view == null ? null
                : new RunFeedbackResponse(view.verdict(), view.issue(), view.detail(), view.updatedAt());
    }
}
