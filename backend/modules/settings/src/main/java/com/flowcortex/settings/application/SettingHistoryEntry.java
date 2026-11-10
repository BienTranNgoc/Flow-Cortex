package com.flowcortex.settings.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One change of a key. Values are typed as the key's declaration ({@link Boolean} or {@link Integer});
 * {@code previousValue} is null for the first write and {@code newValue} is null for a reset.
 * {@code changedBy} is the account id as stored; whether that account still exists is the caller's concern.
 * {@code reason} is null when the change carried none.
 */
public record SettingHistoryEntry(Object previousValue, Object newValue, UUID changedBy, String reason,
        Instant changedAt) {
}
