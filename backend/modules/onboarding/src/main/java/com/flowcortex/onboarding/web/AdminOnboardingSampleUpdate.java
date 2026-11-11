package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sets the onboarding sample, or clears it when {@code workflowId} is null. {@code workflowId} is a
 * string so a malformed id is a field error rather than an unreadable body.
 */
@Schema(name = "AdminOnboardingSampleUpdate")
@SampleFieldsPresent
record AdminOnboardingSampleUpdate(
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        String workflowId,
        @Schema(types = {"string", "null"}, maxLength = 10_000, description = "a single line")
        @Size(max = 10_000)
        @Pattern(regexp = "[^\\r\\n]*")
        String sampleInput,
        @Schema(types = {"string", "null"}, allowableValues = {"vi", "en"})
        @Pattern(regexp = "vi|en")
        String sourceLocale) {
}
