package com.flowcortex.settings.application;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.SettingType;
import java.util.Optional;

/**
 * The one arbiter of every read, in this order: a failed or unparseable stored value degrades; a
 * switch configured off is vetoed; a stored value wins; an integer takes its configured value; a
 * switch takes its configured on, else its code default.
 */
public final class SettingResolution {

    private SettingResolution() {
    }

    public static <T> SettingRead<T> resolve(SettingDefinition<T> definition, Optional<String> storedText,
            boolean readFailed) {
        Optional<T> stored = storedText.flatMap(text -> SettingValues.parseStored(definition, text));
        boolean unparseable = storedText.isPresent() && stored.isEmpty();
        if (readFailed || unparseable) {
            T safe = degradedValue(definition);
            return new SettingRead<>(safe, safe, SettingSource.DEGRADED);
        }
        if (definition.type() == SettingType.INTEGER) {
            return stored
                    .map(value -> new SettingRead<>(value, value, SettingSource.STORED))
                    .orElseGet(() -> {
                        T configured = definition.configuredValue();
                        return new SettingRead<>(configured, configured, SettingSource.CONFIGURED);
                    });
        }
        T configured = definition.configuredValue();
        T decided = stored.orElse(configured == null || Boolean.FALSE.equals(configured)
                ? definition.codeDefault() : configured);
        if (Boolean.FALSE.equals(configured)) {
            return new SettingRead<>(configured, decided, SettingSource.VETOED);
        }
        if (stored.isPresent()) {
            return new SettingRead<>(decided, decided, SettingSource.STORED);
        }
        return new SettingRead<>(decided, decided,
                configured == null ? SettingSource.DEFAULT : SettingSource.CONFIGURED);
    }

    /** A switch fails closed (off); an integer falls back to its configured value. */
    private static <T> T degradedValue(SettingDefinition<T> definition) {
        if (definition.type() == SettingType.SWITCH) {
            return definition.key().type().cast(Boolean.FALSE);
        }
        return definition.configuredValue();
    }
}
