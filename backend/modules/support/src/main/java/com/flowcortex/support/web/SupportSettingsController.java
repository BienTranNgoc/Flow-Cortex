package com.flowcortex.support.web;

import com.flowcortex.support.config.AssistantSwitch;
import com.flowcortex.support.config.SupportProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/support/settings} — what the widget shows before any question: the contact
 * address (null when none is configured) and whether the assistant takes questions.
 */
@RestController
@RequestMapping("/api/v1/support")
class SupportSettingsController {

    private final SupportProperties properties;
    private final AssistantSwitch assistantSwitch;

    SupportSettingsController(SupportProperties properties, AssistantSwitch assistantSwitch) {
        this.properties = properties;
        this.assistantSwitch = assistantSwitch;
    }

    @SecurityRequirements
    @Operation(operationId = "getSupportSettings")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    SettingsResponse settings() {
        return new SettingsResponse(properties.contact().orElse(null), assistantSwitch.available());
    }

    @Schema(name = "SupportSettings")
    record SettingsResponse(
            String contactEmail,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean assistantAvailable) {
    }
}
