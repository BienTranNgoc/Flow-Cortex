package com.flowcortex.settings.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingResolutionTest {

    private static final SettingDefinition<Integer> QUOTA =
            SettingDefinition.integerKey("a.b.member_daily_quota", 1, 1000, () -> 50);

    private static final SettingDefinition<String> MODE = SettingDefinition.enumKey("a.b.mode",
            List.of("off", "shadow", "enforce"), "shadow", () -> "off");

    private static SettingDefinition<Boolean> aSwitch(boolean codeDefault, Boolean configured) {
        return SettingDefinition.switchKey("a.b.enabled", codeDefault, () -> configured);
    }

    private static <T> void assertRead(SettingRead<T> read, T effective, T decided, SettingSource source) {
        assertThat(read).isEqualTo(new SettingRead<>(effective, decided, source));
    }

    @Test
    void storedBeatsConfigured() {
        assertRead(SettingResolution.resolve(QUOTA, Optional.of("80"), false), 80, 80, SettingSource.STORED);
    }

    @Test
    void configuredWhenNothingStored() {
        assertRead(SettingResolution.resolve(QUOTA, Optional.empty(), false), 50, 50, SettingSource.CONFIGURED);
    }

    @Test
    void aConfiguredOffSwitchIsVetoedWhateverIsStored() {
        SettingDefinition<Boolean> off = aSwitch(true, false);

        assertRead(SettingResolution.resolve(off, Optional.of("true"), false), false, true, SettingSource.VETOED);
        assertRead(SettingResolution.resolve(off, Optional.of("false"), false), false, false,
                SettingSource.VETOED);
        assertRead(SettingResolution.resolve(off, Optional.empty(), false), false, true, SettingSource.VETOED);
    }

    @Test
    void aSwitchConfiguredOnWithNothingStoredIsConfigured() {
        assertRead(SettingResolution.resolve(aSwitch(false, true), Optional.empty(), false), true, true,
                SettingSource.CONFIGURED);
    }

    @Test
    void aStoredOffBeatsAConfiguredOn() {
        assertRead(SettingResolution.resolve(aSwitch(true, true), Optional.of("false"), false), false, false,
                SettingSource.STORED);
    }

    @Test
    void anUnsetSwitchTakesItsCodeDefaultAndAStoredValueBeatsIt() {
        SettingDefinition<Boolean> unsetOff = aSwitch(false, null);

        assertRead(SettingResolution.resolve(unsetOff, Optional.empty(), false), false, false,
                SettingSource.DEFAULT);
        assertRead(SettingResolution.resolve(unsetOff, Optional.of("true"), false), true, true,
                SettingSource.STORED);
        assertRead(SettingResolution.resolve(aSwitch(true, null), Optional.empty(), false), true, true,
                SettingSource.DEFAULT);
    }

    @Test
    void aFailedReadDegradesASwitchToOff() {
        assertRead(SettingResolution.resolve(aSwitch(true, true), Optional.empty(), true), false, false,
                SettingSource.DEGRADED);
        assertRead(SettingResolution.resolve(aSwitch(true, true), Optional.of("true"), true), false, false,
                SettingSource.DEGRADED);
    }

    @Test
    void aFailedReadDegradesAnIntegerToConfigured() {
        assertRead(SettingResolution.resolve(QUOTA, Optional.empty(), true), 50, 50, SettingSource.DEGRADED);
    }

    @Test
    void unparseableStoredTextDegrades() {
        assertRead(SettingResolution.resolve(aSwitch(true, true), Optional.of("yes"), false), false, false,
                SettingSource.DEGRADED);
        assertRead(SettingResolution.resolve(QUOTA, Optional.of("1.5"), false), 50, 50, SettingSource.DEGRADED);
        assertRead(SettingResolution.resolve(aSwitch(true, false), Optional.of("yes"), false), false, false,
                SettingSource.DEGRADED);
    }

    @Test
    void anEnumTakesItsStoredValue() {
        assertRead(SettingResolution.resolve(MODE, Optional.of("enforce"), false), "enforce", "enforce",
                SettingSource.STORED);
    }

    @Test
    void anEnumWithNothingStoredIsConfigured() {
        assertRead(SettingResolution.resolve(MODE, Optional.empty(), false), "off", "off",
                SettingSource.CONFIGURED);
    }

    @Test
    void anEnumConfiguredAsUnsetTakesItsCodeDefault() {
        SettingDefinition<String> unset = SettingDefinition.enumKey("a.b.mode", List.of("off", "shadow"), "shadow",
                () -> null);

        assertRead(SettingResolution.resolve(unset, Optional.empty(), false), "shadow", "shadow",
                SettingSource.DEFAULT);
    }

    @Test
    void aFailedReadDegradesAnEnumToConfigured() {
        assertRead(SettingResolution.resolve(MODE, Optional.empty(), true), "off", "off", SettingSource.DEGRADED);
        assertRead(SettingResolution.resolve(MODE, Optional.of("enforce"), true), "off", "off",
                SettingSource.DEGRADED);
    }

    @Test
    void anUnparseableStoredEnumDegradesToConfigured() {
        assertRead(SettingResolution.resolve(MODE, Optional.of("ENFORCE"), false), "off", "off",
                SettingSource.DEGRADED);
        assertRead(SettingResolution.resolve(MODE, Optional.of("maybe"), false), "off", "off",
                SettingSource.DEGRADED);
    }
}
