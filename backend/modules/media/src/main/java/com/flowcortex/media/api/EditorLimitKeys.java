package com.flowcortex.media.api;

import com.flowcortex.settings.api.SettingKey;

/**
 * The video editor's runtime limits, one handle per limit. Read an effective value with
 * {@code settings.get(EditorLimitKeys.X)}; a destructive consumer must not act on a degraded read.
 */
public final class EditorLimitKeys {

    /** Versions kept per project. */
    public static final SettingKey<Integer> PROJECT_MAX_VERSIONS =
            SettingKey.integerKey("media.editor.project_max_versions");
    /** Largest project document, in bytes. */
    public static final SettingKey<Integer> PROJECT_MAX_BYTES =
            SettingKey.integerKey("media.editor.project_max_bytes");
    /** Scenes in one batch generation. */
    public static final SettingKey<Integer> BATCH_MAX_ITEMS =
            SettingKey.integerKey("media.editor.batch_max_items");
    /** Take checks per member per day. */
    public static final SettingKey<Integer> TAKE_CHECK_DAILY_MAX =
            SettingKey.integerKey("media.editor.take_check_daily_max");
    /** Longest clip, in seconds, for voice and music separation. */
    public static final SettingKey<Integer> STEMS_MAX_DURATION_SEC =
            SettingKey.integerKey("media.editor.stems_max_duration_sec");
    /** Longest clip, in seconds, for speaker separation. */
    public static final SettingKey<Integer> SPEAKERS_MAX_DURATION_SEC =
            SettingKey.integerKey("media.editor.speakers_max_duration_sec");
    /** Longest clip, in seconds, for derive. */
    public static final SettingKey<Integer> DERIVE_MAX_DURATION_SEC =
            SettingKey.integerKey("media.editor.derive_max_duration_sec");
    /** Longest clip, in seconds, for beat analysis. */
    public static final SettingKey<Integer> BEATS_MAX_DURATION_SEC =
            SettingKey.integerKey("media.editor.beats_max_duration_sec");
    /** Longest clip, in seconds, for one-track audio extraction. */
    public static final SettingKey<Integer> AUDIO_TOOLS_MAX_DURATION_SEC =
            SettingKey.integerKey("media.editor.audio_tools_max_duration_sec");

    private EditorLimitKeys() {
    }
}
