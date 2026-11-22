package com.flowcortex.settings.api;

import java.util.List;

/**
 * A declared key as an admin surface shows it. Values are {@link Boolean}, {@link Integer} or
 * {@link String} by {@code type}. {@code options} is empty unless the key is an enum; {@code min} and
 * {@code max} are null unless it is an integer; {@code stored} is null when nothing usable is stored;
 * {@code configured} is null when the server configuration leaves a switch or enum unset;
 * {@code codeDefault} is null for an integer.
 */
public record SettingDescription(String name, SettingType type, List<String> options, Integer min, Integer max,
        Object effective, Object decided, SettingSource source, Object stored, Object configured,
        Object codeDefault) {
}
