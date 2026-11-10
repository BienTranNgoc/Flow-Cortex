package com.flowcortex.settings.web;

import com.flowcortex.settings.api.SettingActors;
import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.application.SettingHistoryEntry;
import com.flowcortex.settings.application.SettingView;
import com.flowcortex.settings.application.SettingsService;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/admin/settings} — every declared key with the layer that decided it, one write and
 * reset per key, and each key's history. The {@code {key:.+}} variable keeps the dots of a key name.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
class AdminSettingsController {

    private static final int MAX_PAGE = 100_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final SettingsService settings;
    private final SettingActors actors;

    AdminSettingsController(SettingsService settings, SettingActors actors) {
        this.settings = settings;
        this.actors = actors;
    }

    /** A JSON boolean for a SWITCH key, a JSON integer for an INTEGER key. */
    @Schema(name = "AdminSettingUpdate")
    record AdminSettingUpdate(
            @Schema(requiredMode = RequiredMode.REQUIRED, implementation = Object.class, oneOf = {Boolean.class, Integer.class})
            @NotNull JsonNode value) {
    }

    /**
     * Values are JSON booleans or integers by {@code type}. {@code storedValue}, {@code updatedBy} and
     * {@code updatedAt} are null when nothing is stored; {@code defaultValue} is null for an integer;
     * {@code configuredValue} is null for a switch whose configured value is unset; {@code updatedBy} is
     * the admin's email, null when that account is gone.
     */
    @Schema(name = "AdminSetting")
    record AdminSetting(
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = RequiredMode.REQUIRED) String group,
            @Schema(requiredMode = RequiredMode.REQUIRED) SettingType type,
            @Schema(requiredMode = RequiredMode.REQUIRED, oneOf = {Boolean.class, Integer.class}) Object value,
            @Schema(requiredMode = RequiredMode.REQUIRED, oneOf = {Boolean.class, Integer.class})
            Object decidedValue,
            @Schema(requiredMode = RequiredMode.REQUIRED) SettingSource source,
            @Schema(nullable = true, oneOf = {Boolean.class, Integer.class}) Object storedValue,
            @Schema(nullable = true, oneOf = {Boolean.class, Integer.class}) Object configuredValue,
            @Schema(nullable = true, oneOf = {Boolean.class, Integer.class}) Object defaultValue,
            @Schema(nullable = true) Integer min,
            @Schema(nullable = true) Integer max,
            @Schema(nullable = true) String updatedBy,
            @Schema(nullable = true) Instant updatedAt) {
    }

    @Schema(name = "AdminSettingList")
    record AdminSettingList(@Schema(requiredMode = RequiredMode.REQUIRED) List<AdminSetting> items) {
    }

    /**
     * {@code previousValue} is null for the first write, {@code newValue} for a reset, and
     * {@code changedBy} (an email) when that account is gone.
     */
    @Schema(name = "AdminSettingChange")
    record AdminSettingChange(
            @Schema(nullable = true, oneOf = {Boolean.class, Integer.class}) Object previousValue,
            @Schema(nullable = true, oneOf = {Boolean.class, Integer.class}) Object newValue,
            @Schema(nullable = true) String changedBy,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant changedAt) {
    }

    @Operation(operationId = "listAdminSettings")
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSettingList list() {
        List<SettingView> views = settings.views();
        Map<UUID, String> emails = emailsOf(views.stream().map(AdminSettingsController::actorOf).toList());
        return new AdminSettingList(views.stream().map(view -> toResponse(view, emails)).toList());
    }

    @Operation(operationId = "updateAdminSetting")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "not-found (unknown key)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "setting.invalidValue (errors.value)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PLATFORM_SETTING_UPDATE)
    @PutMapping(value = "/{key:.+}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSetting update(Principal caller, @PathVariable String key, @Valid @RequestBody AdminSettingUpdate body) {
        return single(settings.setRaw(key, body.value(), UUID.fromString(caller.getName())));
    }

    @Operation(operationId = "resetAdminSetting")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "not-found (unknown key)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PLATFORM_SETTING_UPDATE)
    @DeleteMapping(value = "/{key:.+}", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminSetting reset(Principal caller, @PathVariable String key) {
        return single(settings.reset(key, UUID.fromString(caller.getName())));
    }

    @Operation(operationId = "listAdminSettingHistory")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "not-found (unknown key)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(value = "/{key:.+}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    PageView<AdminSettingChange> history(@PathVariable String key,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        PageView<SettingHistoryEntry> entries = settings.history(key, page, size);
        Map<UUID, String> emails = emailsOf(entries.items().stream().map(SettingHistoryEntry::changedBy).toList());
        return entries.map(entry -> new AdminSettingChange(entry.previousValue(), entry.newValue(),
                entry.changedBy() == null ? null : emails.get(entry.changedBy()), entry.changedAt()));
    }

    private AdminSetting single(SettingView view) {
        return toResponse(view, emailsOf(Collections.singletonList(actorOf(view))));
    }

    /** One lookup for every account a response names; an account that is gone is absent from the map. */
    private Map<UUID, String> emailsOf(Collection<UUID> ids) {
        Set<UUID> distinct = new LinkedHashSet<>(ids);
        distinct.remove(null);
        return distinct.isEmpty() ? Map.of() : actors.emailsOf(distinct);
    }

    private static UUID actorOf(SettingView view) {
        return view.change() == null ? null : view.change().actor();
    }

    private static AdminSetting toResponse(SettingView view, Map<UUID, String> emails) {
        SettingDefinition<?> definition = view.definition();
        SettingChange change = view.change();
        String name = definition.name();
        return new AdminSetting(name, name.substring(0, name.indexOf('.')), definition.type(),
                view.read().effective(), view.read().decided(), view.read().source(), view.storedValue(),
                definition.configuredValue(), definition.codeDefault(), definition.min(), definition.max(),
                change == null ? null : emails.get(change.actor()), change == null ? null : change.at());
    }
}
