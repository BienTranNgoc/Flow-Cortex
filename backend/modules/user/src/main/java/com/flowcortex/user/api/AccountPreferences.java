package com.flowcortex.user.api;

import com.flowcortex.user.preferences.PrivacySettings;
import com.flowcortex.user.preferences.UiPreferences;
import java.util.UUID;

/**
 * Per-account language, UI preferences and privacy settings. Every method refuses a suspended
 * account with {@code AccessDeniedException} and an unknown account with {@code NotFoundException}.
 * The merge methods merge key by key: a {@code null} key of the given value keeps the stored one.
 */
public interface AccountPreferences {

    PreferencesView get(UUID userId);

    /** Stores {@code vi} or {@code en}; any other value is an {@code IllegalArgumentException} and nothing is stored. */
    void setLocale(UUID userId, String locale);

    /** Returns the merged UI set. */
    UiPreferences mergeUi(UUID userId, UiPreferences patch);

    /** Returns the merged set with the defaults filled in. */
    PrivacySettings mergePrivacy(UUID userId, PrivacySettings patch);
}
