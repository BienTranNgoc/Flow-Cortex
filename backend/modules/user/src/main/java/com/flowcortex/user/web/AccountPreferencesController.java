package com.flowcortex.user.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.user.api.AccountPreferences;
import com.flowcortex.user.api.PreferencesView;
import com.flowcortex.user.preferences.PreferencePatch;
import com.flowcortex.user.preferences.PrivacySettings;
import com.flowcortex.user.preferences.UiPreferences;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/auth/profile/preferences}: the caller's saved language, UI preferences and privacy
 * settings. The PATCH bodies are parsed by {@link PreferencePatch} before the service is called.
 */
@RestController
@RequestMapping("/api/v1/auth/profile/preferences")
class AccountPreferencesController {

    private static final LocalizedMessage INVALID = new LocalizedMessage("profile.preferences.invalid");

    private final AccountPreferences preferences;

    AccountPreferencesController(AccountPreferences preferences) {
        this.preferences = preferences;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    PreferencesView get(@AuthenticationPrincipal Jwt jwt) {
        return preferences.get(subjectId(jwt));
    }

    @PutMapping(value = "/locale", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    LocaleResponse setLocale(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LocaleRequest request) {
        preferences.setLocale(subjectId(jwt), request.locale());
        return new LocaleResponse(request.locale());
    }

    @PatchMapping(value = "/ui", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Body keys: theme (light|dark), sidebarMode (pinned|rail)",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = UiPreferences.class))))
    UiPreferences patchUi(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        UiPreferences patch = PreferencePatch.parseUi(body)
                .orElseThrow(() -> new LocalizedStatusException(HttpStatus.BAD_REQUEST, INVALID));
        return preferences.mergeUi(subjectId(jwt), patch);
    }

    @PatchMapping(value = "/privacy", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(description = "Body keys: profile, activity, message (everyone|followers|only_me), stats (show|hide)",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = PrivacySettings.class))))
    PrivacySettings patchPrivacy(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        PrivacySettings patch = PreferencePatch.parsePrivacy(body)
                .orElseThrow(() -> new LocalizedStatusException(HttpStatus.BAD_REQUEST, INVALID));
        return preferences.mergePrivacy(subjectId(jwt), patch);
    }

    private static UUID subjectId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record LocaleRequest(@NotNull @Pattern(regexp = "vi|en") String locale) {
    }

    record LocaleResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locale) {
    }
}
