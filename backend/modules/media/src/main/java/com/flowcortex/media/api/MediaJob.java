package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The job message, shape of {@code contracts/media-job.schema.json}. */
public record MediaJob(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull UUID jobId,
        @NotNull UUID runId,
        @NotNull UUID correlationId,
        @NotNull @Min(1) Integer stepOrder,
        @NotNull MediaCapability capability,
        @NotEmpty List<@NotNull @Valid MediaJobInput> inputs,
        @NotNull Map<String, Object> params,
        @NotNull @Pattern(regexp = OUTPUT_PREFIX_PATTERN) String outputPrefix,
        @NotNull @Min(1) Integer attempt,
        @NotNull @Min(1) Integer maxRuntimeSeconds,
        @NotNull @Min(1) Long maxInputBytes,
        @NotNull Instant issuedAt) {

    /** Same pattern as {@code outputPrefix} in the job schema: the worker writes only under its step. */
    public static final String OUTPUT_PREFIX_PATTERN =
            "^runs/[0-9a-f-]{36}/steps/[1-9][0-9]*/(?:attempts/[1-9][0-9]*/)?$";

    /** The {@code outputPrefix} of step {@code stepOrder} of run {@code runId}. */
    public static String outputPrefix(UUID runId, int stepOrder) {
        return "runs/" + runId + "/steps/" + stepOrder + "/";
    }

    public static String outputPrefix(UUID runId, int stepOrder, int attempt) {
        return outputPrefix(runId, stepOrder) + "attempts/" + attempt + "/";
    }

    /**
     * Whether {@code objectKey} names an object below {@code prefix}: something follows the prefix
     * and none of its segments is empty, {@code .} or {@code ..}, so the key cannot resolve elsewhere.
     */
    public static boolean isBelow(String prefix, String objectKey) {
        if (!objectKey.startsWith(prefix) || objectKey.length() == prefix.length()) {
            return false;
        }
        for (String segment : objectKey.substring(prefix.length()).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** {@code correlationId} is the run id; the schema cannot express the equality. */
    @AssertTrue(message = "correlationId must equal runId")
    @JsonIgnore
    public boolean isCorrelatedToRun() {
        return correlationId == null || runId == null || correlationId.equals(runId);
    }
}
