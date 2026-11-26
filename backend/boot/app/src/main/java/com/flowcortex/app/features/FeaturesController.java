package com.flowcortex.app.features;

import com.flowcortex.app.features.FeatureSwitchCatalog.Reading;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feature switches the member app adapts to. {@code passwordReset}, {@code runCancellation},
 * {@code supportWidget} and {@code supportAssistant} are runtime switches read on every request; the
 * rest are server configuration read at startup (a change needs a restart). Public, since the sign-in
 * and registration pages need them before there is a session. Lives in {@code :boot:app} because the
 * switches belong to several modules.
 */
@RestController
@RequestMapping("/api/v1")
public class FeaturesController {

    private final FeatureSwitchCatalog catalog;

    FeaturesController(FeatureSwitchCatalog catalog) {
        this.catalog = catalog;
    }

    @SecurityRequirements
    @GetMapping(value = "/features", produces = MediaType.APPLICATION_JSON_VALUE)
    public FeaturesResponse features() {
        return response(catalog.read());
    }

    private static FeaturesResponse response(List<Reading> readings) {
        Map<String, Boolean> byName = new HashMap<>();
        readings.forEach(reading -> byName.put(reading.name(), reading.value()));
        return new FeaturesResponse(byName.get("emailConfirmation"), byName.get("phoneVerification"),
                byName.get("uploadContentCheck"), byName.get("runCancellation"), byName.get("credits"),
                byName.get("passwordReset"), byName.get("supportWidget"), byName.get("supportAssistant"),
                byName.get("personalAccessTokens"), byName.get("onboarding"), byName.get("chat"), byName.get("drive"));
    }

    public record FeaturesResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean emailConfirmation,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean phoneVerification,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean uploadContentCheck,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean runCancellation,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean credits,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean passwordReset,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean supportWidget,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean supportAssistant,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean personalAccessTokens,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean onboarding,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean chat,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean drive) {
    }
}
