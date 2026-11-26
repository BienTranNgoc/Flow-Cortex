package com.flowcortex.user.accounts;

import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.user.roles.RbacRuntimeSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.BooleanNode;

/**
 * {@code /api/v1/admin/rbac/runtime} — the role-edit kill switch. Both routes need the super-admin key
 * {@code system.role.manage}, and neither is ever refused by the switch itself.
 */
@RestController
@RequestMapping("/api/v1/admin/rbac/runtime")
class AdminRbacRuntimeController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminRbacRuntimeController.class);
    private static final int REASON_MAX = 2000;

    private final Settings settings;
    private final UserDirectory users;

    AdminRbacRuntimeController(Settings settings, UserDirectory users) {
        this.settings = settings;
        this.users = users;
    }

    @Schema(name = "RbacRuntimeUpdate")
    record RbacRuntimeUpdate(
            @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull Boolean mutationsEnabled,
            @Size(max = REASON_MAX) String reason) {
    }

    /**
     * {@code configuredValue} is the server configuration, null when it leaves the switch to the admin;
     * {@code updatedBy} is the email of the admin who stored the value, null when nothing is stored or
     * that account is gone.
     */
    @Schema(name = "RbacRuntime")
    record RbacRuntime(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean mutationsEnabled,
            @Schema(requiredMode = RequiredMode.REQUIRED) SettingSource source,
            @Schema(nullable = true) Boolean configuredValue,
            @Schema(nullable = true) String updatedBy,
            @Schema(nullable = true) Instant updatedAt) {
    }

    @Operation(operationId = "getRbacRuntime")
    @RequirePermission(Permission.SYSTEM_ROLE_MANAGE)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    RbacRuntime get() {
        return view(describe());
    }

    @Operation(operationId = "updateRbacRuntime")
    @AdminAudit(value = "rbac_runtime.update", capture = {"mutationsEnabled", "reason"})
    @RequirePermission(Permission.SYSTEM_ROLE_MANAGE)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    RbacRuntime update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RbacRuntimeUpdate body) {
        UUID actor = UUID.fromString(jwt.getSubject());
        Object stored = describe().stored();
        SettingDescription written = settings.write(RbacRuntimeSettings.MUTATIONS_ENABLED.name(),
                SettingScope.RBAC_RUNTIME, BooleanNode.valueOf(body.mutationsEnabled()), actor, body.reason());
        if (!body.mutationsEnabled().equals(stored)) {
            LOG.info("rbac.runtime outcome=updated enabled={} actor={}", body.mutationsEnabled(), actor);
        }
        return view(written);
    }

    private SettingDescription describe() {
        return settings.describe(RbacRuntimeSettings.MUTATIONS_ENABLED.name(), SettingScope.RBAC_RUNTIME)
                .orElseThrow(() -> new IllegalStateException(
                        "Setting " + RbacRuntimeSettings.MUTATIONS_ENABLED.name() + " is not declared"));
    }

    private RbacRuntime view(SettingDescription description) {
        Optional<SettingChange> change = settings.lastChange(RbacRuntimeSettings.MUTATIONS_ENABLED);
        String updatedBy = change.map(SettingChange::actor)
                .map(actor -> users.findEmailsByIds(List.of(actor)).get(actor)).orElse(null);
        return new RbacRuntime((Boolean) description.effective(), description.source(),
                (Boolean) description.configured(), updatedBy, change.map(SettingChange::at).orElse(null));
    }
}
