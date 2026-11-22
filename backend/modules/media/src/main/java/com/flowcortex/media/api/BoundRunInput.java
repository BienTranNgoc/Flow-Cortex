package com.flowcortex.media.api;

import java.util.UUID;

/**
 * An upload bound to a run. {@code mediaAssetId} is the upload's asset; {@code bucket} and
 * {@code objectKey} locate the run's copy of it, which is what an asset registered from this input points at.
 */
public record BoundRunInput(UUID mediaAssetId, UUID ownerId, String bindingKey, String kind, String bucket,
        String objectKey, String mimeType) {
}
