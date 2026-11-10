package com.flowcortex.media.api;

import java.util.UUID;

/**
 * A registered file of a step attempt. {@code kind} is the upload kind ({@code video}, {@code image},
 * {@code audio}, or another value the media allowlist does not accept); {@code inMediaBucket} is false for
 * an object outside the media bucket, which no member-facing asset may point at.
 */
public record StoredMediaFile(UUID mediaAssetId, UUID ownerId, String kind, String bucket, String objectKey,
        String mimeType, long bytes, boolean inMediaBucket) {
}
