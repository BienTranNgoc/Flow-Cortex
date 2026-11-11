package com.flowcortex.settings.api;

/**
 * A typed handle on a declared setting.
 *
 * @param name the key, {@code <module>.<feature>.<name>} in lower snake case
 * @param type the Java type of its value: {@link Boolean} for a switch, {@link Integer} for an integer,
 *         {@link String} for an enum
 */
public record SettingKey<T>(String name, Class<T> type) {

    public static SettingKey<Boolean> switchKey(String name) {
        return new SettingKey<>(name, Boolean.class);
    }

    public static SettingKey<Integer> integerKey(String name) {
        return new SettingKey<>(name, Integer.class);
    }

    public static SettingKey<String> enumKey(String name) {
        return new SettingKey<>(name, String.class);
    }
}
