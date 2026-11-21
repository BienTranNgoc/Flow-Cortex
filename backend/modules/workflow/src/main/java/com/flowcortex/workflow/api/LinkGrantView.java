package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/** A member admitted through the share key; {@code name} is {@code null} when the account no longer exists. */
public record LinkGrantView(UUID userId, String name, Instant grantedAt) {
}
