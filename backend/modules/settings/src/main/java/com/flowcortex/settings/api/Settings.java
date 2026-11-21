package com.flowcortex.settings.api;

import java.util.Optional;
import java.util.UUID;

/** Reads and writes declared runtime settings. */
public interface Settings {

    /** Decides the key's value; never throws for a store problem, it serves the degraded position. */
    <T> SettingRead<T> get(SettingKey<T> key);

    /** The stamp of the stored value; empty when nothing is stored. */
    Optional<SettingChange> lastChange(SettingKey<?> key);

    /** Stores the value and records the change; the value is validated against the declaration. */
    <T> void set(SettingKey<T> key, T value, UUID actor);
}
