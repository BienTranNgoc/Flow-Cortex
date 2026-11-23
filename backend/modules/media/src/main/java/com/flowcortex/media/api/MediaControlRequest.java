package com.flowcortex.media.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Control check request, shape of {@code contracts/media-control-request.schema.json}, sent by a
 * video worker before submitting and periodically while processing a delivery.
 */
public record MediaControlRequest(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull UUID runId,
        @NotNull UUID jobId,
        @NotNull @Min(1) Integer attempt) {
}
