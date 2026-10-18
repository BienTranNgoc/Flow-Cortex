package com.flowcortex.app.features;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feature switches the member app adapts to, read once at startup (a change needs a restart).
 * Public, since the sign-in and registration pages need them before there is a session. Lives in
 * {@code :boot:app} because the switches belong to several modules.
 */
@RestController
@RequestMapping("/api/v1")
public class FeaturesController {

    private final FeaturesResponse features;

    public FeaturesController(
            @Value("${flowcortex.auth.email-confirmation.enabled:false}") boolean emailConfirmation,
            @Value("${flowcortex.phone-verification.enabled:false}") boolean phoneVerification,
            @Value("${flowcortex.media.upload.content-check.enabled:true}") boolean uploadContentCheck,
            @Value("${flowcortex.workflow.cancel.enabled:false}") boolean runCancellation,
            @Value("${flowcortex.credits.enabled:false}") boolean credits) {
        this.features = new FeaturesResponse(emailConfirmation, phoneVerification, uploadContentCheck,
                runCancellation, credits);
    }

    @SecurityRequirements
    @GetMapping(value = "/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public FeaturesResponse features() {
        return features;
    }

    public record FeaturesResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean emailConfirmation,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean phoneVerification,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean uploadContentCheck,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean runCancellation,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean credits) {
    }
}
