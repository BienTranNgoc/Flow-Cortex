package com.flowcortex.user.api;

import com.flowcortex.user.preferences.PrivacySettings;
import com.flowcortex.user.preferences.UiPreferences;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A member's saved preferences: {@code locale} is {@code null} until first saved, {@code ui} holds
 * only the keys the member chose, {@code privacy} always carries all four keys.
 */
public record PreferencesView(
        @Schema(nullable = true, allowableValues = {"vi", "en"}) String locale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UiPreferences ui,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PrivacySettings privacy) {
}
