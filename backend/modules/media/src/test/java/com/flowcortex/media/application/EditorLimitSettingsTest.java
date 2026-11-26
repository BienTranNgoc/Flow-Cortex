package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.application.SettingsRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorLimitSettingsTest {

    private static final EditorLimitProperties STARTING_VALUES =
            new EditorLimitProperties(50, 2_000_000, 4, 14, 600, 600, 600, 600, 3600);

    @ParameterizedTest
    @CsvSource({
            "project_max_versions, 1, 200, 50",
            "project_max_bytes, 100000, 10000000, 2000000",
            "batch_max_items, 1, 12, 4",
            "take_check_daily_max, 1, 200, 14",
            "stems_max_duration_sec, 60, 3600, 600",
            "speakers_max_duration_sec, 60, 1800, 600",
            "derive_max_duration_sec, 10, 3600, 600",
            "beats_max_duration_sec, 30, 1200, 600",
            "audio_tools_max_duration_sec, 60, 14400, 3600"
    })
    void eachLimitIsDeclaredWithItsBounds(String name, int min, int max, int startingValue) {
        Map<String, SettingDefinition<?>> byName = definitions(STARTING_VALUES).stream()
                .collect(Collectors.toMap(SettingDefinition::name, Function.identity()));

        SettingDefinition<?> definition = byName.get("media.editor." + name);

        assertThat(definition).as("declaration of media.editor.%s", name).isNotNull();
        assertThat(definition.type()).isEqualTo(SettingType.INTEGER);
        assertThat(definition.min()).isEqualTo(min);
        assertThat(definition.max()).isEqualTo(max);
        assertThat(definition.configuredValue()).isEqualTo(startingValue);
    }

    @Test
    void theNineDefinitionsPassTheRegistryGuard() {
        SettingsRegistry registry = new SettingsRegistry(definitions(STARTING_VALUES));

        assertThat(registry.all()).extracting(SettingDefinition::name).containsExactly(
                "media.editor.audio_tools_max_duration_sec",
                "media.editor.batch_max_items",
                "media.editor.beats_max_duration_sec",
                "media.editor.derive_max_duration_sec",
                "media.editor.project_max_bytes",
                "media.editor.project_max_versions",
                "media.editor.speakers_max_duration_sec",
                "media.editor.stems_max_duration_sec",
                "media.editor.take_check_daily_max");
    }

    @Test
    void aStartingValueAboveItsBoundIsRefusedNamingTheKey() {
        EditorLimitProperties speakersTooLong =
                new EditorLimitProperties(50, 2_000_000, 4, 14, 600, 3600, 600, 600, 3600);

        assertThatThrownBy(() -> new SettingsRegistry(definitions(speakersTooLong)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.editor.speakers_max_duration_sec");
    }

    private static List<SettingDefinition<?>> definitions(EditorLimitProperties properties) {
        EditorLimitSettings settings = new EditorLimitSettings();
        return List.of(
                settings.projectMaxVersionsSetting(properties),
                settings.projectMaxBytesSetting(properties),
                settings.batchMaxItemsSetting(properties),
                settings.takeCheckDailyMaxSetting(properties),
                settings.stemsMaxDurationSecSetting(properties),
                settings.speakersMaxDurationSecSetting(properties),
                settings.deriveMaxDurationSecSetting(properties),
                settings.beatsMaxDurationSecSetting(properties),
                settings.audioToolsMaxDurationSecSetting(properties));
    }
}
