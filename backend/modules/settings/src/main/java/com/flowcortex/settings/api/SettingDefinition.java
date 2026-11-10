package com.flowcortex.settings.api;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The declaration of one setting: its key, type, bounds and the layers beneath a stored value.
 * A switch has a code default and a configured value that may be unset ({@code null}); an integer
 * has inclusive bounds and a configured value that is always set, and no separate code default.
 */
public final class SettingDefinition<T> {

    private final SettingKey<T> key;
    private final SettingType type;
    private final T codeDefault;
    private final Integer min;
    private final Integer max;
    private final Supplier<T> configured;

    private SettingDefinition(SettingKey<T> key, SettingType type, T codeDefault, Integer min, Integer max,
            Supplier<T> configured) {
        this.key = key;
        this.type = type;
        this.codeDefault = codeDefault;
        this.min = min;
        this.max = max;
        this.configured = Objects.requireNonNull(configured, "configured");
    }

    public static SettingDefinition<Boolean> switchKey(String name, boolean codeDefault,
            Supplier<Boolean> configured) {
        return new SettingDefinition<>(SettingKey.switchKey(name), SettingType.SWITCH, codeDefault, null, null,
                configured);
    }

    public static SettingDefinition<Integer> integerKey(String name, int min, int max,
            Supplier<Integer> configured) {
        return new SettingDefinition<>(SettingKey.integerKey(name), SettingType.INTEGER, null, min, max,
                configured);
    }

    public SettingKey<T> key() {
        return key;
    }

    public String name() {
        return key.name();
    }

    public SettingType type() {
        return type;
    }

    /** The code default of a switch; {@code null} for an integer. */
    public T codeDefault() {
        return codeDefault;
    }

    /** Inclusive minimum of an integer; {@code null} for a switch. */
    public Integer min() {
        return min;
    }

    /** Inclusive maximum of an integer; {@code null} for a switch. */
    public Integer max() {
        return max;
    }

    /** The configured value, read from the owning module's properties on every call; may be {@code null}. */
    public T configuredValue() {
        return configured.get();
    }
}
