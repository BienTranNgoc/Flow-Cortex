package com.flowcortex.app.features;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/features} — the member feature switches in {@code /features} order with the
 * settings key that changes each one. Changing, resetting and history go through
 * {@code /api/v1/admin/settings/{key}}.
 */
@RestController
@RequestMapping("/api/v1/admin/features")
class AdminFeaturesController {

    private final FeatureSwitchCatalog catalog;

    AdminFeaturesController(FeatureSwitchCatalog catalog) {
        this.catalog = catalog;
    }

    /** {@code settingKey} is null for a switch only the server configuration changes. */
    @Schema(name = "AdminFeature")
    record AdminFeature(
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(nullable = true) String settingKey,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean value) {
    }

    @Schema(name = "AdminFeatureList")
    record AdminFeatureList(@Schema(requiredMode = RequiredMode.REQUIRED) List<AdminFeature> items) {
    }

    @Operation(operationId = "listAdminFeatures")
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminFeatureList list() {
        return new AdminFeatureList(catalog.read().stream()
                .map(reading -> new AdminFeature(reading.name(), reading.settingKey(), reading.value()))
                .toList());
    }
}
