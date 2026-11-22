package com.flowcortex.workflow.api;

import java.time.Instant;

/** A member's pin on a workflow; every field is null when there is none. */
public record VersionPinView(Integer pinnedVersion, String reason, Instant pinnedAt) {
}
