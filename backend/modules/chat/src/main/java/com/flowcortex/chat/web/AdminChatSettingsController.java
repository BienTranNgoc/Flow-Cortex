package com.flowcortex.chat.web;

import com.flowcortex.chat.config.ChatSwitch;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/admin/chat/settings} — the chat console switch and who flipped it last. */
@RestController
@RequestMapping("/api/v1/admin/chat/settings")
class AdminChatSettingsController {

    private final ChatSwitch chatSwitch;
    private final UserDirectory users;
    private final Clock clock;

    AdminChatSettingsController(ChatSwitch chatSwitch, UserDirectory users, Clock clock) {
        this.chatSwitch = chatSwitch;
        this.users = users;
        this.clock = clock;
    }

    @Schema(name = "AdminChatSettingsUpdate")
    record SettingsUpdate(@NotNull Boolean enabled) {
    }

    /** {@code updatedBy} is the admin's email, null when never flipped or that account is gone. */
    @Schema(name = "AdminChatSettings")
    record AdminSettings(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
            String updatedBy,
            Instant updatedAt) {
    }

    @Operation(operationId = "getAdminChatSettings")
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSettings settings() {
        return view();
    }

    @Operation(operationId = "updateAdminChatSettings")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.enabled)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PLATFORM_SETTING_UPDATE)
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSettings update(Principal caller, @Valid @RequestBody SettingsUpdate body) {
        chatSwitch.setConsole(body.enabled(), UUID.fromString(caller.getName()), clock.instant());
        return view();
    }

    private AdminSettings view() {
        ChatSwitch.Stamp stamp = chatSwitch.stamp();
        String updatedBy = stamp.updatedBy() == null ? null
                : users.findEmailsByIds(List.of(stamp.updatedBy())).get(stamp.updatedBy());
        return new AdminSettings(chatSwitch.consoleEnabled(), updatedBy, stamp.updatedAt());
    }
}
