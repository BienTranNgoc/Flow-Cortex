package com.flowcortex.drive.item;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code drive_item} row. A text item holds {@code textValue}; an upload or run-output item holds the
 * object's bucket and key and the file facts instead, and a run-output item also its run and step.
 */
record DriveItemRow(UUID id, UUID spaceId, UUID ownerId, String name, String contentType, String semanticRole,
        String textValue, String source, String bucket, String objectKey, String fileName, String mimeType,
        Long sizeBytes, UUID runId, Integer stepOrder, Instant createdAt, Instant updatedAt) {

    static final String SOURCE_TEXT = "text";
    static final String SOURCE_UPLOAD = "upload";
    static final String SOURCE_RUN_OUTPUT = "run_output";

    boolean isMedia() {
        return !SOURCE_TEXT.equals(source);
    }
}
