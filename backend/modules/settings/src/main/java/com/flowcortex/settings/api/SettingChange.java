package com.flowcortex.settings.api;

import java.time.Instant;
import java.util.UUID;

/** Who stored the current value of a key, and when. */
public record SettingChange(UUID actor, Instant at) {
}
