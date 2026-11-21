package com.flowcortex.drive.item;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A named output of a step of one of the caller's runs: what a media item points at and a suggestion offers. */
record RunOutputRef(
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID runId,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull Integer stepOrder,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotBlank @Size(max = 255) String name) {

    DriveItemService.RunOutput toCommand() {
        return new DriveItemService.RunOutput(runId, stepOrder, name);
    }
}
