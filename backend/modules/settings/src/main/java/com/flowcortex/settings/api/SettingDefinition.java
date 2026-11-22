package com.flowcortex.settings.api;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The declaration of one setting: its key, type, bounds and the layers beneath a stored value.
 * A switch has a code default and a configured value that may be unset ({@code null}); an integer
 * has inclusive bounds and a configured value that is always set, and no separate code default;
 * an enum has distinct options, a code default among them and a configured value that may be unset.
 * A declaration is {@link SettingScope#GENERAL} unless {@link #inScope} names another scope.
 */
public final class SettingDefinition<T> {

    private final SettingKey<T> key;
    private final SettingType type;
    private final T codeDefault;
    private final Integer min;
    private final Integer max;
    private final List<String> options;
    private final SettingScope scope;
    private final Supplier<T> configured;

    private SettingDefinition(SettingKey<T> key, SettingType type, T codeDefault, Integer min, Integer max,
            List<String> options, SettingScope scope, Supplier<T> configured) {
        this.key = key;
        this.type = type;
        this.codeDefault = codeDefault;
        this.min = min;
        this.max = max;
        this.options = options;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.configured = Objects.requireNonNull(configured, "configured");
    }

    public static SettingDefinition<Boolean> switchKey(String name, boolean codeDefault,
            Supplier<Boolean> configured) {
        return new SettingDefinition<>(SettingKey.switchKey(name), SettingType.SWITCH, codeDefault, null, null,
                List.of(), SettingScope.GENERAL, configured);
    }

    public static SettingDefinition<Integer> integerKey(String name, int min, int max,
            Supplier<Integer> configured) {
        return new SettingDefinition<>(SettingKey.integerKey(name), SettingType.INTEGER, null, min, max,
                List.of(), SettingScope.GENERAL, configured);
    }

    /** Whether the options are distinct, non-empty and include the code default is checked at startup. */
    public static SettingDefinition<String> enumKey(String name, List<String> options, String codeDefault,
            Supplier<String> configured) {
        return new SettingDefinition<>(SettingKey.enumKey(name), SettingType.ENUM, codeDefault, null, null,
                List.copyOf(options), SettingScope.GENERAL, configured);
    }

    /** The same declaration in another scope. */
    public SettingDefinition<T> inScope(SettingScope other) {
        return new SettingDefinition<>(key, type, codeDefault, min, max, options, other, configured);
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

    public SettingScope scope() {
        return scope;
    }

    /** The code default of a switch or enum; {@code null} for an integer. */
    public T codeDefault() {
        return codeDefault;
    }

    /** Inclusive minimum of an integer; {@code null} otherwise. */
    public Integer min() {
        return min;
    }

    /** Inclusive maximum of an integer; {@code null} otherwise. */
    public Integer max() {
        return max;
    }

    /** The options of an enum in declaration order; empty for any other type. */
    public List<String> options() {
        return options;
    }

    /** The configured value, read from the owning module's properties on every call; may be {@code null}. */
    public T configuredValue() {
        return configured.get();
    }
}
