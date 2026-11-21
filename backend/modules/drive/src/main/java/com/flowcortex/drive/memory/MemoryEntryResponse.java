package com.flowcortex.drive.memory;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One Memory entry; {@code url} signs the stored file of a media entry and is absent for a text entry. */
record MemoryEntryResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
        @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"text", "image", "video", "audio"})
        String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String title,
        String previewText,
        String fileName,
        String mimeType,
        @Schema(requiredMode = RequiredMode.REQUIRED) long sizeBytes,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant resultAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean pinned,
        String url,
        Instant urlExpiresAt) {

    static MemoryEntryResponse of(MemoryResultRepository.Stored row, String url, Instant urlExpiresAt) {
        ContentType kind = row.kind();
        return new MemoryEntryResponse(row.id(), row.runId(), row.stepOrder(), kind.wire(), row.title(),
                row.previewText(), row.fileName(), row.mimeType(), row.sizeBytes(), row.resultAt(), row.pinned(),
                url, urlExpiresAt);
    }
}
