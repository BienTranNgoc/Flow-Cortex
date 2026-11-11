package com.flowcortex.content.web;

import com.flowcortex.content.media.ContentMediaService.MediaItem;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One library item; {@code url} is the public route that redirects to its stored copy. */
@Schema(name = "ContentMediaItem")
record ContentMediaResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"image", "video"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String contentType,
        @Schema(requiredMode = RequiredMode.REQUIRED) long sizeBytes,
        @Schema(requiredMode = RequiredMode.REQUIRED) String fileName,
        @Schema(nullable = true, description = "Null when the uploader's account no longer exists") String uploaderEmail,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) String url) {

    static ContentMediaResponse of(MediaItem item) {
        return new ContentMediaResponse(item.id(), item.kind(), item.contentType(), item.sizeBytes(), item.fileName(),
                item.uploaderEmail(), item.createdAt(), PublicContentMediaController.PATH + "/" + item.id());
    }
}
