package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Saves the translation of the sample input; a blank {@code input} removes it. */
@Schema(name = "AdminOnboardingSampleTranslationUpdate")
record AdminOnboardingSampleTranslationUpdate(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "the sourceHash the admin was looking at")
        @NotNull
        @Pattern(regexp = "[0-9a-f]{64}")
        String sourceHash,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 10_000, description = "a single line")
        @NotNull
        @Size(max = 10_000)
        @Pattern(regexp = "[^\\r\\n]*")
        String input) {
}
