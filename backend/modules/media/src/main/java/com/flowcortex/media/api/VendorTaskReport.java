package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code vendorTask} block of a {@code vendor_task} progress message, shape of
 * {@code contracts/media-progress.schema.json}. Without {@code vendorTaskId} and with status
 * {@code running} it records a newly submitted vendor job; any other status is a verdict on a row,
 * named by {@code vendorTaskId} or, for a job the worker just submitted, by vendor and vendor job id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VendorTaskReport(
        UUID vendorTaskId,
        @NotBlank @Size(max = 32) String vendor,
        @NotBlank @Size(max = 200) String model,
        @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
        @Pattern(regexp = "[0-9a-f]{64}") String requestKey,
        @NotBlank @Size(max = 200) String vendorJobId,
        @Size(max = MAX_HANDLE_PROPERTIES) Map<String, Object> handle,
        @NotNull Status status,
        Instant vendorCreatedAt) {

    public static final int MAX_HANDLE_PROPERTIES = 16;
    public static final int MAX_HANDLE_BYTES = 2048;

    private static final JsonMapper HANDLE_MAPPER = JsonMapper.builder().build();

    /** The closed {@code vendorTask.status} enum of the progress schema. */
    public enum Status {
        RUNNING("running"),
        FAILED("failed"),
        EXPIRED("expired"),
        GONE("gone");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /** Whether {@code handle} serializes to at most {@link #MAX_HANDLE_BYTES} bytes; null and empty fit. */
    public static boolean handleFits(Map<String, Object> handle) {
        return handle == null || HANDLE_MAPPER.writeValueAsBytes(handle).length <= MAX_HANDLE_BYTES;
    }

    @AssertTrue(message = "handle must serialize to at most 2048 bytes")
    @JsonIgnore
    public boolean isHandleWithinLimit() {
        return handleFits(handle);
    }

    @AssertTrue(message = "a new running vendor job needs fingerprint, requestKey, handle and vendorCreatedAt")
    @JsonIgnore
    public boolean isCompleteWhenNew() {
        return vendorTaskId != null
                || status != Status.RUNNING
                || (fingerprint != null && requestKey != null && handle != null && vendorCreatedAt != null);
    }
}
