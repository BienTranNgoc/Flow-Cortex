package com.flowcortex.drive.item;

import com.flowcortex.drive.item.DriveItemService.ItemView;
import com.flowcortex.media.api.MediaDownload;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * An item as the member sees it. A text item carries {@code textValue}; a media item carries a time-limited view
 * {@code url} with its expiry, and never the bucket or key.
 */
record DriveItemResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"text", "image", "video", "audio"})
        String contentType,
        @Schema(requiredMode = RequiredMode.REQUIRED) String semanticRole,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"text", "upload", "run_output"})
        String source,
        String textValue,
        String url,
        Instant urlExpiresAt,
        String fileName,
        String mimeType,
        Long sizeBytes,
        UUID runId,
        Integer stepOrder,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

    static DriveItemResponse from(ItemView view) {
        DriveItemRow row = view.item();
        MediaDownload download = view.download();
        return new DriveItemResponse(row.id(), row.name(), row.contentType(), row.semanticRole(), row.source(),
                row.textValue(), download == null ? null : download.url(),
                download == null ? null : download.expiresAt(), row.fileName(), row.mimeType(), row.sizeBytes(),
                row.runId(), row.stepOrder(), row.createdAt(), row.updatedAt());
    }
}
