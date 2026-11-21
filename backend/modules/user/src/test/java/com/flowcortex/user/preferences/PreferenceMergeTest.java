package com.flowcortex.user.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PreferenceMergeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void uiMergeKeepsAbsentKeys() {
        UiPreferences stored = new UiPreferences(Theme.DARK, null);

        UiPreferences merged = PreferenceMerge.mergeUi(stored, new UiPreferences(null, SidebarMode.RAIL));

        assertThat(merged).isEqualTo(new UiPreferences(Theme.DARK, SidebarMode.RAIL));
    }

    @Test
    void privacyReadAppliesDefaultsUnderStoredValues() {
        PrivacySettings read = PreferenceMerge.withDefaults(new PrivacySettings(null, Visibility.ONLY_ME, null, null));

        assertThat(read).isEqualTo(new PrivacySettings(Visibility.EVERYONE, Visibility.ONLY_ME,
                Visibility.EVERYONE, StatsVisibility.SHOW));
    }

    @Test
    void privacyMergeReturnsTheFullFourKeySet() {
        PrivacySettings merged = PreferenceMerge.mergePrivacy(PrivacySettings.EMPTY,
                new PrivacySettings(null, null, null, StatsVisibility.HIDE));

        assertThat(merged).isEqualTo(new PrivacySettings(Visibility.EVERYONE, Visibility.FOLLOWERS,
                Visibility.EVERYONE, StatsVisibility.HIDE));
    }

    @Test
    void patchRefusesAnUnknownKey() {
        assertThat(PreferencePatch.parseUi(json("{\"fontSize\":\"large\"}"))).isEmpty();
        assertThat(PreferencePatch.parsePrivacy(json("{\"theme\":\"dark\"}"))).isEmpty();
    }

    @Test
    void patchRefusesAnExplicitNull() {
        assertThat(PreferencePatch.parseUi(json("{\"theme\":null}"))).isEmpty();
        assertThat(PreferencePatch.parsePrivacy(json("{\"stats\":null}"))).isEmpty();
    }

    @Test
    void patchRefusesAValueOutsideTheEnum() {
        assertThat(PreferencePatch.parseUi(json("{\"theme\":\"blue\"}"))).isEmpty();
        assertThat(PreferencePatch.parseUi(json("{\"theme\":1}"))).isEmpty();
        assertThat(PreferencePatch.parsePrivacy(json("{\"profile\":\"friends\"}"))).isEmpty();
    }

    @Test
    void patchRefusesANonObjectBody() {
        assertThat(PreferencePatch.parseUi(json("[]"))).isEmpty();
        assertThat(PreferencePatch.parsePrivacy(json("\"dark\""))).isEmpty();
        assertThat(PreferencePatch.parseUi(null)).isEmpty();
    }

    @Test
    void patchKeepsAbsentKeysAbsent() {
        Optional<UiPreferences> ui = PreferencePatch.parseUi(json("{\"sidebarMode\":\"rail\"}"));
        Optional<PrivacySettings> privacy = PreferencePatch.parsePrivacy(json("{\"stats\":\"hide\"}"));

        assertThat(ui).contains(new UiPreferences(null, SidebarMode.RAIL));
        assertThat(privacy).contains(new PrivacySettings(null, null, null, StatsVisibility.HIDE));
    }

    @Test
    void storedMapsIgnoreValuesTheEnumsNoLongerKnow() {
        assertThat(UiPreferences.fromMap(Map.of("theme", "sepia", "sidebarMode", "pinned")))
                .isEqualTo(new UiPreferences(null, SidebarMode.PINNED));
    }
}
