package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The owner's verdict on a run. {@code verdict} is {@code up} or {@code down}; {@code issue} is required for
 * {@code down} (one of {@code wrong_request}, {@code low_quality}, {@code off_brand}, {@code technical_error},
 * {@code inappropriate}, {@code other}) and refused for {@code up}; {@code detail} is optional free text of at most
 * 1,000 characters. The rules decide, so a refused value answers 400 naming its field.
 */
record RunFeedbackRequest(
        @Schema(allowableValues = {"up", "down"}) String verdict,
        @Schema(allowableValues = {"wrong_request", "low_quality", "off_brand", "technical_error", "inappropriate",
                "other"}) String issue,
        @Schema(maxLength = 1000) String detail) {
}
