package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A partial change: an absent or null field stays as it is. */
record UpdateWorkflowRequest(
        @Size(max = 255) @Pattern(regexp = "(?s)\\s*\\S.*", message = "must not be blank") String name,
        Boolean shared,
        @Schema(allowableValues = {"draft", "published"}) @Pattern(regexp = "draft|published") String status,
        Boolean allowFork) {
}
