package com.flowcortex.settings.application;

import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingRead;

/**
 * A declared key as the console shows it: the decided read and every layer beneath it.
 * {@code storedValue} is null when nothing usable is stored; {@code change} is the stamp of the stored
 * value, null when nothing is stored.
 */
public record SettingView(SettingDefinition<?> definition, SettingRead<?> read, Object storedValue,
        SettingChange change) {
}
