package com.flowcortex.asset.domain;

import java.util.UUID;

/**
 * One stored object a step produced. {@code kind} is the media module's label on the way in and the
 * MIME-derived {@code video}/{@code image}/{@code audio} once {@link FinalOutputSelector} accepted it.
 */
public record OutputCandidate(
        UUID mediaAssetId,
        UUID ownerId,
        UUID stepId,
        String kind,
        String bucket,
        String objectKey,
        String mimeType,
        long bytes,
        boolean inMediaBucket) {

    OutputCandidate withKind(String newKind) {
        return new OutputCandidate(mediaAssetId, ownerId, stepId, newKind, bucket, objectKey, mimeType, bytes, inMediaBucket);
    }
}
