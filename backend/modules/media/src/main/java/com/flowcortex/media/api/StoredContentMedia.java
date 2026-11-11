package com.flowcortex.media.api;

/** A content media file kept in the media bucket at {@code content/<id>}, with its type, kind and stored size. */
public record StoredContentMedia(String bucket, String objectKey, String contentType, String kind, long sizeBytes) {
}
