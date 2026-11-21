package com.flowcortex.drive.item;

import com.flowcortex.drive.item.DriveItemService.NewItem;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import java.util.UUID;

/**
 * Create body. A text item sends {@code textValue}; a media item sends exactly one of {@code uploadId} (granted
 * by {@code POST /api/v1/drive/uploads}) or {@code runOutput}. Field-level rules answer with {@code errors.<field>}.
 */
record CreateDriveItemRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"text", "image", "video", "audio"})
        String contentType,
        @Schema(requiredMode = RequiredMode.REQUIRED) String semanticRole,
        String textValue,
        UUID uploadId,
        @Valid RunOutputRef runOutput) {

    NewItem toCommand() {
        return new NewItem(name, contentType, semanticRole, textValue, uploadId,
                runOutput == null ? null : runOutput.toCommand());
    }
}
