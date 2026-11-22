package com.flowcortex.media.api;

import java.util.UUID;

/**
 * Published in the transaction that binds a run's input copies, at most once per binding, heard after
 * commit. {@code ownerId} owns every bound upload.
 */
public record RunInputsBound(UUID runId, UUID ownerId) {
}
