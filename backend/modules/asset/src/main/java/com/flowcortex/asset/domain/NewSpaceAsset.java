package com.flowcortex.asset.domain;

import java.util.UUID;

/**
 * One library asset to insert. A {@code run} asset names its producing step; an {@code upload}
 * asset has none and points at the run's copy of the upload.
 */
public record NewSpaceAsset(
        UUID id,
        UUID spaceId,
        UUID ownerId,
        UUID mediaAssetId,
        String source,
        String kind,
        String title,
        String titleBase,
        int titleVersion,
        String bucket,
        String objectKey,
        String mimeType,
        long bytes,
        UUID runId,
        UUID runStepId,
        UUID workflowId,
        String workflowName) {
}
