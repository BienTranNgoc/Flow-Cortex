package com.flowcortex.media.api;

/** An image kept in the media bucket as an attachment, with the type and size of its upload. */
public record StoredAttachment(String bucket, String objectKey, String contentType, long sizeBytes) {
}
