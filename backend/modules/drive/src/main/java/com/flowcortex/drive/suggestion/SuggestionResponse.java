package com.flowcortex.drive.suggestion;

import com.flowcortex.drive.domain.SuggestionProjector.Suggestion;
import com.flowcortex.drive.suggestion.SuggestionsService.SuggestionView;
import com.flowcortex.media.api.MediaDownload;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One candidate to keep as a Drive item. A text or input candidate carries its full {@code textValue}, or
 * {@code textValue} null with {@code tooLong} true past the item cap; a media candidate carries {@code runOutput}
 * (what {@code POST /api/v1/drive/items} takes) and a time-limited preview {@code url}.
 */
record SuggestionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"text", "image", "video", "audio"})
        String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String previewText,
        String textValue,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean tooLong,
        String roleHint,
        @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
        String stepType,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant at,
        RunOutputView runOutput,
        String url,
        Instant urlExpiresAt) {

    /** The output a media candidate points at. */
    record RunOutputView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
            @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name) {
    }

    static SuggestionResponse from(SuggestionView view) {
        Suggestion s = view.suggestion();
        MediaDownload preview = view.preview();
        RunOutputView output = s.output() == null ? null
                : new RunOutputView(s.runId(), s.stepOrder(), s.output().name());
        return new SuggestionResponse(s.id(), s.kind().wire(), s.previewText(), s.textValue(), s.tooLong(),
                s.roleHint(), s.workflowName(), s.stepType(), s.runId(), s.at(), output,
                preview == null ? null : preview.url(), preview == null ? null : preview.expiresAt());
    }
}
