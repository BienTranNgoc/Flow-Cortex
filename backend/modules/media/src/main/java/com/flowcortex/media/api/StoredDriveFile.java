package com.flowcortex.media.api;

/**
 * A file kept in the media bucket for a Drive item: where it lives, a plain server-chosen file name for
 * downloads, its type, the size of the stored (hardened) bytes and its kind ({@code video}, {@code audio} or
 * {@code image}).
 */
public record StoredDriveFile(String bucket, String objectKey, String fileName, String mimeType, long bytes,
        String kind) {
}
