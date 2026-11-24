package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request body to clone a finished Product Video run and redo only selected clip or lip-sync steps. */
public record RedoProductVideoRequest(
        @NotEmpty @Size(max = 3) List<@NotNull @Positive Integer> redoSteps,
        @Size(max = 500) String sceneNote,
        Boolean acknowledgeCost) {
}
