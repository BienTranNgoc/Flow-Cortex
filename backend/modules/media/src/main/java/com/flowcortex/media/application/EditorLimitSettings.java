package com.flowcortex.media.application;

import static com.flowcortex.media.api.EditorLimitKeys.AUDIO_TOOLS_MAX_DURATION_SEC;
import static com.flowcortex.media.api.EditorLimitKeys.BATCH_MAX_ITEMS;
import static com.flowcortex.media.api.EditorLimitKeys.BEATS_MAX_DURATION_SEC;
import static com.flowcortex.media.api.EditorLimitKeys.DERIVE_MAX_DURATION_SEC;
import static com.flowcortex.media.api.EditorLimitKeys.PROJECT_MAX_BYTES;
import static com.flowcortex.media.api.EditorLimitKeys.PROJECT_MAX_VERSIONS;
import static com.flowcortex.media.api.EditorLimitKeys.SPEAKERS_MAX_DURATION_SEC;
import static com.flowcortex.media.api.EditorLimitKeys.STEMS_MAX_DURATION_SEC;
import static com.flowcortex.media.api.EditorLimitKeys.TAKE_CHECK_DAILY_MAX;

import com.flowcortex.settings.api.SettingDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the video editor's runtime limits with their bounds. The configured layer reads
 * {@link EditorLimitProperties} on every call; the settings registry refuses startup when a
 * configured value lies outside its bounds.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EditorLimitProperties.class)
public class EditorLimitSettings {

    @Bean
    SettingDefinition<Integer> projectMaxVersionsSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(PROJECT_MAX_VERSIONS.name(), 1, 200, properties::projectMaxVersions);
    }

    @Bean
    SettingDefinition<Integer> projectMaxBytesSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(PROJECT_MAX_BYTES.name(), 100_000, 10_000_000,
                properties::projectMaxBytes);
    }

    @Bean
    SettingDefinition<Integer> batchMaxItemsSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(BATCH_MAX_ITEMS.name(), 1, 12, properties::batchMaxItems);
    }

    @Bean
    SettingDefinition<Integer> takeCheckDailyMaxSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(TAKE_CHECK_DAILY_MAX.name(), 1, 200, properties::takeCheckDailyMax);
    }

    @Bean
    SettingDefinition<Integer> stemsMaxDurationSecSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(STEMS_MAX_DURATION_SEC.name(), 60, 3600,
                properties::stemsMaxDurationSec);
    }

    @Bean
    SettingDefinition<Integer> speakersMaxDurationSecSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(SPEAKERS_MAX_DURATION_SEC.name(), 60, 1800,
                properties::speakersMaxDurationSec);
    }

    @Bean
    SettingDefinition<Integer> deriveMaxDurationSecSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(DERIVE_MAX_DURATION_SEC.name(), 10, 3600,
                properties::deriveMaxDurationSec);
    }

    @Bean
    SettingDefinition<Integer> beatsMaxDurationSecSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(BEATS_MAX_DURATION_SEC.name(), 30, 1200,
                properties::beatsMaxDurationSec);
    }

    @Bean
    SettingDefinition<Integer> audioToolsMaxDurationSecSetting(EditorLimitProperties properties) {
        return SettingDefinition.integerKey(AUDIO_TOOLS_MAX_DURATION_SEC.name(), 60, 14_400,
                properties::audioToolsMaxDurationSec);
    }
}
