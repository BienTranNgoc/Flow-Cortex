package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/**
 * The progress message, shape of {@code contracts/media-progress.schema.json}: {@code started} once
 * per delivery, then heartbeats. {@code percent} and {@code message} are optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaProgress(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull UUID jobId,
        @NotNull UUID runId,
        @NotNull @Min(1) Integer attempt,
        @NotNull @Min(0) Integer deliveryCount,
        @NotNull Kind kind,
        @Min(0) @Max(100) Integer percent,
        String message,
        @NotNull Instant at) {

    /** The closed {@code kind} enum of the progress schema. */
    public enum Kind {
        STARTED("started"),
        HEARTBEAT("heartbeat");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
