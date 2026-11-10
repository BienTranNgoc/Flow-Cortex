package com.flowcortex.workflow.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new private draft workflow; a {@code shared} field is ignored, visibility has its own route. */
@JsonIgnoreProperties(ignoreUnknown = true)
record CreateWorkflowRequest(@NotBlank @Size(max = 255) String name) {
}
