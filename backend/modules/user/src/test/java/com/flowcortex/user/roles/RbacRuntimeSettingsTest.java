package com.flowcortex.user.roles;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.application.SettingResolution;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RbacRuntimeSettingsTest {

    private static SettingDefinition<Boolean> definition(Boolean configured) {
        return RbacRuntimeSettings.definition(() -> configured);
    }

    private static void assertRead(SettingRead<Boolean> read, boolean effective, SettingSource source) {
        assertThat(read.effective()).isEqualTo(effective);
        assertThat(read.source()).isEqualTo(source);
    }

    @Test
    void unsetConfigurationAndNothingStoredIsOnByDefault() {
        assertRead(SettingResolution.resolve(definition(null), Optional.empty(), false), true,
                SettingSource.DEFAULT);
    }

    @Test
    void aConfiguredFalseVetoesAStoredTrue() {
        assertRead(SettingResolution.resolve(definition(false), Optional.of("true"), false), false,
                SettingSource.VETOED);
    }

    @Test
    void aStoredFalseTurnsItOff() {
        assertRead(SettingResolution.resolve(definition(null), Optional.of("false"), false), false,
                SettingSource.STORED);
    }

    @Test
    void anUnreadableStoreIsOff() {
        assertRead(SettingResolution.resolve(definition(null), Optional.empty(), true), false,
                SettingSource.DEGRADED);
    }

    @Test
    void theKeyIsInTheRbacRuntimeScope() {
        SettingDefinition<Boolean> definition = definition(null);

        assertThat(definition.scope()).isEqualTo(SettingScope.RBAC_RUNTIME);
        assertThat(definition.name()).isEqualTo("user.rbac.mutations_enabled");
        assertThat(RbacRuntimeSettings.MUTATIONS_ENABLED.name()).isEqualTo(definition.name());
    }
}
