package com.flowcortex.settings.application;

import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingRead;

/**
 * A declared key as the console shows it: the decided read and every layer beneath it.
 * {@code storedValue} is null when nothing usable is stored; {@code change} is the stamp of the stored
 * value, null when nothing is stored.
 */
public record SettingView(SettingDefinition<?> definition, SettingRead<?> read, Object storedValue,
        SettingChange change) {

    /** The key as every admin surface describes it; the change stamp stays on this view. */
    public SettingDescription description() {
        return new SettingDescription(definition.name(), definition.type(), definition.options(),
                definition.min(), definition.max(), read.effective(), read.decided(), read.source(), storedValue,
                definition.configuredValue(), definition.codeDefault());
    }
}
