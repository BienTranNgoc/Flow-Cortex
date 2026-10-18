package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/** The run's options as the member chose them; an omitted voice is {@code female}. */
record RunOptionsRequest(
        @Schema(allowableValues = {"female", "male"}, defaultValue = "female")
        @Pattern(regexp = "female|male", message = "must be female or male") String voice) {
}
