package com.flowcortex.settings.api;

/**
 * One decided read.
 *
 * @param effective the value callers act on
 * @param decided the value before any environment veto
 * @param source the layer that decided
 */
public record SettingRead<T>(T effective, T decided, SettingSource source) {
}
