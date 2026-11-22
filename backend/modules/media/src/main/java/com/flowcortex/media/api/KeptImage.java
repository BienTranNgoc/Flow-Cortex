package com.flowcortex.media.api;

/** A profile image kept in the media bucket: the stripped bytes' type and size, and the pixel size of its header. */
public record KeptImage(String bucket, String objectKey, String contentType, long bytes, int width, int height) {
}
