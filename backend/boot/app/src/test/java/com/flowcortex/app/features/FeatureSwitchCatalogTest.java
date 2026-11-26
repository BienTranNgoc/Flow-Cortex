package com.flowcortex.app.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.app.features.FeatureSwitchCatalog.Reading;
import com.flowcortex.app.features.FeaturesController.FeaturesResponse;
import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.api.Settings;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

class FeatureSwitchCatalogTest {

    private static final String CANCELLATION_KEY = "workflow.run_cancellation.enabled";
    private static final Set<String> RUNTIME_KEYS = Set.of("user.pw_reset.enabled", CANCELLATION_KEY,
            "support.help_center.enabled", "support.assistant.enabled");

    private final Settings settings = mock(Settings.class);

    @BeforeEach
    void everyKeyReadsOffUnlessACaseSaysOtherwise() {
        when(settings.get(any(SettingKey.class))).thenReturn(read(false));
    }

    @Test
    void startsWhenEveryCatalogKeyIsADeclaredGeneralSwitch() {
        declare(RUNTIME_KEYS, SettingType.SWITCH);

        assertThatCode(() -> catalog(new MockEnvironment()).afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void refusesStartupNamingAnUndeclaredKey() {
        declare(Set.of("user.pw_reset.enabled", "support.help_center.enabled", "support.assistant.enabled"),
                SettingType.SWITCH);

        assertThatThrownBy(() -> catalog(new MockEnvironment()).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(CANCELLATION_KEY);
    }

    @Test
    void refusesStartupNamingADeclaredKeyThatIsNotASwitch() {
        declare(RUNTIME_KEYS, SettingType.SWITCH);
        when(settings.describe(CANCELLATION_KEY, SettingScope.GENERAL))
                .thenReturn(Optional.of(description(CANCELLATION_KEY, SettingType.INTEGER)));

        assertThatThrownBy(() -> catalog(new MockEnvironment()).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(CANCELLATION_KEY);
    }

    @Test
    void catalogFieldsEqualFeaturesResponseComponents() {
        List<String> components = Arrays.stream(FeaturesResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(catalog(new MockEnvironment()).read().stream().map(Reading::name).toList())
                .containsExactlyElementsOf(components);
    }

    @Test
    void aRuntimeFieldReadsItsKeyOnEveryCall() {
        when(settings.get(SettingKey.switchKey("user.pw_reset.enabled")))
                .thenReturn(read(true), read(false));
        FeatureSwitchCatalog catalog = catalog(new MockEnvironment());

        assertThat(values(catalog).get("passwordReset")).isTrue();
        assertThat(values(catalog).get("passwordReset")).isFalse();
    }

    @Test
    void theVetoedValueIsWhatTheFieldServes() {
        when(settings.get(SettingKey.switchKey("support.help_center.enabled")))
                .thenReturn(new SettingRead<>(false, true, SettingSource.VETOED));

        assertThat(values(catalog(new MockEnvironment())).get("supportWidget")).isFalse();
    }

    static Stream<Arguments> environmentOnlyFields() {
        return Stream.of(
                Arguments.of("emailConfirmation", "flowcortex.auth.email-confirmation.enabled", true),
                Arguments.of("phoneVerification", "flowcortex.phone-verification.enabled", true),
                Arguments.of("uploadContentCheck", "flowcortex.media.upload.content-check.enabled", false),
                Arguments.of("credits", "flowcortex.credits.enabled", true),
                Arguments.of("personalAccessTokens", "flowcortex.auth.personal-access-tokens.enabled", true),
                Arguments.of("onboarding", "flowcortex.onboarding.enabled", true),
                Arguments.of("chat", "flowcortex.chat.enabled", true),
                Arguments.of("drive", "flowcortex.spaces.enabled", true));
    }

    @ParameterizedTest
    @MethodSource("environmentOnlyFields")
    void eachEnvironmentOnlyFieldReadsItsProperty(String field, String property, boolean value) {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        for (RecordComponent component : FeaturesResponse.class.getRecordComponents()) {
            expected.put(component.getName(), false);
        }
        expected.put("uploadContentCheck", true);
        expected.put(field, value);

        Map<String, Boolean> actual = values(catalog(new MockEnvironment().withProperty(property,
                String.valueOf(value))));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void onlyTheFourRuntimeFieldsNameASettingKey() {
        Map<String, String> keys = new LinkedHashMap<>();
        catalog(new MockEnvironment()).read().forEach(reading -> keys.put(reading.name(), reading.settingKey()));

        assertThat(keys).containsEntry("credits", null).containsEntry("runCancellation", CANCELLATION_KEY)
                .containsEntry("passwordReset", "user.pw_reset.enabled")
                .containsEntry("supportWidget", "support.help_center.enabled")
                .containsEntry("supportAssistant", "support.assistant.enabled");
        assertThat(keys.values().stream().filter(Objects::nonNull)).hasSize(4);
    }

    private FeatureSwitchCatalog catalog(MockEnvironment environment) {
        return new FeatureSwitchCatalog(settings, environment);
    }

    private void declare(Set<String> keys, SettingType type) {
        when(settings.declaredKeys(SettingScope.GENERAL)).thenReturn(keys);
        for (String key : keys) {
            when(settings.describe(eq(key), eq(SettingScope.GENERAL)))
                    .thenReturn(Optional.of(description(key, type)));
        }
    }

    private static SettingDescription description(String key, SettingType type) {
        return new SettingDescription(key, type, List.of(), null, null, true, true, SettingSource.DEFAULT, null,
                null, true);
    }

    private static SettingRead<Boolean> read(boolean value) {
        return new SettingRead<>(value, value, SettingSource.STORED);
    }

    private static Map<String, Boolean> values(FeatureSwitchCatalog catalog) {
        return catalog.read().stream().collect(Collectors.toMap(Reading::name, Reading::value, (a, b) -> a,
                LinkedHashMap::new));
    }
}
