package com.flowcortex.user.preferences;

/** Key-by-key merge of a patch over stored preferences: an absent (null) patch key keeps the stored one. */
final class PreferenceMerge {

    private PreferenceMerge() {
    }

    static UiPreferences mergeUi(UiPreferences stored, UiPreferences patch) {
        return new UiPreferences(
                patch.theme() != null ? patch.theme() : stored.theme(),
                patch.sidebarMode() != null ? patch.sidebarMode() : stored.sidebarMode());
    }

    /** The merged set with defaults filled, so the answer always carries all four keys. */
    static PrivacySettings mergePrivacy(PrivacySettings stored, PrivacySettings patch) {
        return withDefaults(storedOver(stored, patch));
    }

    /** The stored keys only, without defaults, for writing back. */
    static PrivacySettings storedOver(PrivacySettings stored, PrivacySettings patch) {
        return new PrivacySettings(
                patch.profile() != null ? patch.profile() : stored.profile(),
                patch.activity() != null ? patch.activity() : stored.activity(),
                patch.message() != null ? patch.message() : stored.message(),
                patch.stats() != null ? patch.stats() : stored.stats());
    }

    static PrivacySettings withDefaults(PrivacySettings stored) {
        return new PrivacySettings(
                stored.profile() != null ? stored.profile() : Visibility.EVERYONE,
                stored.activity() != null ? stored.activity() : Visibility.FOLLOWERS,
                stored.message() != null ? stored.message() : Visibility.EVERYONE,
                stored.stats() != null ? stored.stats() : StatsVisibility.SHOW);
    }
}
