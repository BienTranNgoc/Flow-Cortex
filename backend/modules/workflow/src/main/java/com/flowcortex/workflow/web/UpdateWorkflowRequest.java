package com.flowcortex.workflow.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A partial change: an absent or null field stays as it is; a {@code shared} field is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record UpdateWorkflowRequest(
        @Size(max = 255) @Pattern(regexp = "(?s)\\s*\\S.*", message = "must not be blank") String name,
        @Schema(allowableValues = {"draft", "published"}) @Pattern(regexp = "draft|published") String status) {
}
