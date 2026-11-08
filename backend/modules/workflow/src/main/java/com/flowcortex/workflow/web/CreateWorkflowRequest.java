package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new draft workflow. {@code shared} is false when absent. */
record CreateWorkflowRequest(@NotBlank @Size(max = 255) String name, Boolean shared) {
}
