package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
        @NotNull Instant at,
        @Valid VendorTaskReport vendorTask) {

    public MediaProgress(String schemaVersion, UUID jobId, UUID runId, Integer attempt, Integer deliveryCount,
            Kind kind, Integer percent, String message, Instant at) {
        this(schemaVersion, jobId, runId, attempt, deliveryCount, kind, percent, message, at, null);
    }

    @AssertTrue(message = "vendorTask must be present exactly when kind is vendor_task")
    @JsonIgnore
    public boolean isVendorTaskPairedWithKind() {
        return kind == null || (kind == Kind.VENDOR_TASK) == (vendorTask != null);
    }

    /** The closed {@code kind} enum of the progress schema. */
    public enum Kind {
        STARTED("started"),
        HEARTBEAT("heartbeat"),
        VENDOR_TASK("vendor_task");

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
