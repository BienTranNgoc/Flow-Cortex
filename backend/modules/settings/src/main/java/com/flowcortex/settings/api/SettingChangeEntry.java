package com.flowcortex.settings.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded change as stored: values are the stored text, {@code previousValue} is null for the first
 * write and {@code newValue} for a reset. {@code actor} may name an account that no longer exists and
 * {@code reason} is null when none was given.
 */
public record SettingChangeEntry(UUID id, String name, String previousValue, String newValue, UUID actor,
        String reason, Instant changedAt) {
}
