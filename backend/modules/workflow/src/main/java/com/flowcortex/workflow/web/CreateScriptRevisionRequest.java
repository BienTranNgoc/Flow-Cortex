package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateScriptRevisionRequest(
        @NotBlank @Size(max = 8000) String content,
        @NotNull Long expectedVersion) {
}
