package com.flowcortex.support.assistant;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.support.config.AssistantSwitch;
import com.flowcortex.support.web.SupportCaller;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/support/settings} — the assistant's console switch, who flipped it last, and
 * what else stops the assistant today (the environment switch, the daily ceiling).
 */
@RestController
@RequestMapping("/api/v1/admin/support/settings")
class AdminSupportSettingsController {

    private final SupportCaller caller;
    private final AssistantSwitch assistantSwitch;
    private final QuotaService quotas;
    private final UserDirectory users;
    private final Clock clock;

    AdminSupportSettingsController(SupportCaller caller, AssistantSwitch assistantSwitch, QuotaService quotas,
            UserDirectory users, Clock clock) {
        this.caller = caller;
        this.assistantSwitch = assistantSwitch;
        this.quotas = quotas;
        this.users = users;
        this.clock = clock;
    }

    @Schema(name = "AdminSupportSettingsUpdate")
    record SettingsUpdate(@NotNull Boolean assistantEnabled) {
    }

    /** {@code updatedBy} is the admin's email, null when never flipped or that account is gone. */
    @Schema(name = "AdminSupportSettings")
    record AdminSettings(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean assistantEnabled,
            String updatedBy,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean envEnabled,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean ceilingReachedToday,
            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1") int dailyCeiling) {
    }

    @Operation(operationId = "getAdminSupportSettings")
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSettings settings() {
        return view();
    }

    @Operation(operationId = "updateAdminSupportSettings")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.assistantEnabled)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PLATFORM_SETTING_UPDATE)
    @AdminAudit(value = "support_setting.update", capture = "assistantEnabled")
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSettings update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SettingsUpdate body) {
        CurrentUser admin = caller.require(jwt);
        assistantSwitch.setConsole(body.assistantEnabled(), admin.id(), clock.instant());
        return view();
    }

    private AdminSettings view() {
        AssistantSwitch.Stamp stamp = assistantSwitch.stamp();
        String updatedBy = stamp.updatedBy() == null ? null
                : users.findEmailsByIds(List.of(stamp.updatedBy())).get(stamp.updatedBy());
        return new AdminSettings(assistantSwitch.consoleEnabled(), updatedBy, stamp.updatedAt(),
                assistantSwitch.envEnabled(), quotas.ceilingReachedToday(), quotas.dailyCeiling());
    }
}
