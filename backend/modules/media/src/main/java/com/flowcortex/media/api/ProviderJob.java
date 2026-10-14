package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Provider job envelope consumed from {@code provider.jobs}. */
public record ProviderJob(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull UUID jobId,
        @NotNull UUID runId,
        @NotNull UUID correlationId,
        @NotNull @Min(1) Integer stepOrder,
        @NotBlank String capability,
        @NotNull Map<String, Object> params,
        @NotNull @Pattern(regexp = ProviderJob.OUTPUT_PREFIX_PATTERN) String outputPrefix,
        @NotNull @Min(1) Integer attempt,
        @NotNull @Min(1) Integer maxRuntimeSeconds,
        @NotNull Instant issuedAt) {

    /**
     * Same pattern as {@code outputPrefix} in the provider job schema: attempt-scoped only, unlike
     * {@link MediaJob#OUTPUT_PREFIX_PATTERN}, which still accepts the shape without an attempt.
     */
    public static final String OUTPUT_PREFIX_PATTERN =
            "^runs/[0-9a-f-]{36}/steps/[1-9][0-9]*/attempts/[1-9][0-9]*/$";

    @AssertTrue(message = "correlationId must equal runId")
    @JsonIgnore
    public boolean isCorrelatedToRun() {
        return correlationId == null || runId == null || correlationId.equals(runId);
    }
}
