package com.flowcortex.settings.api;

import com.flowcortex.shared.web.PageView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reads and writes declared runtime settings. */
public interface Settings {

    /** Decides the key's value; never throws for a store problem, it serves the degraded position. */
    <T> SettingRead<T> get(SettingKey<T> key);

    /** The stamp of the stored value; empty when nothing is stored. */
    Optional<SettingChange> lastChange(SettingKey<?> key);

    /** Stores the value and records the change; the value is validated against the declaration. */
    <T> void set(SettingKey<T> key, T value, UUID actor);

    /** The names declared in the scope, from declarations alone: nothing is read from the store. */
    Set<String> declaredKeys(SettingScope scope);

    /** Every key of the scope in key order, each as decided now; a store problem degrades a read, never throws. */
    List<SettingDescription> describe(SettingScope scope);

    /** The key as decided now; empty when it is undeclared or declared in another scope. */
    Optional<SettingDescription> describe(String name, SettingScope scope);

    /**
     * Parses and stores the request value for a key of the scope and records the change with its reason
     * (trimmed, null when blank). A key undeclared in the scope is a {@code NotFoundException}, a refused
     * value an {@code UnprocessableEntityException}, a reason over 2000 characters an
     * {@link IllegalArgumentException}. Answers the key as it now reads.
     */
    SettingDescription write(String name, SettingScope scope, JsonNode value, UUID actor, String reason);

    /**
     * One page of the scope's changes, newest first; {@code name} narrows it to one key, which must be
     * declared in the scope or the call is a {@code NotFoundException}.
     */
    PageView<SettingChangeEntry> history(SettingScope scope, Optional<String> name, int page, int size);
}
