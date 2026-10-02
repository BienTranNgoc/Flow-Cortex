package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
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

/**
 * The result message, shape of {@code contracts/media-result.schema.json}. {@code outputs} and
 * {@code data} are optional; {@code error} is required on an error result and absent otherwise.
 * Null fields are omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaResult(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull UUID jobId,
        @NotNull UUID runId,
        @NotNull @Min(1) Integer attempt,
        @NotNull @Min(0) Integer deliveryCount,
        @NotNull Status status,
        List<@NotNull @Valid MediaOutput> outputs,
        Map<String, Object> data,
        @Valid MediaError error,
        @NotNull Instant finishedAt,
        @NotEmpty String workerId) {

    /** The schema's two branches: an {@code error} block exactly when {@code status} is {@code error}. */
    @AssertTrue(message = "error must be present exactly when status is error")
    @JsonIgnore
    public boolean isErrorBlockConsistent() {
        return status == null || (status == Status.ERROR) == (error != null);
    }

    /** The closed {@code status} enum of the result schema. */
    public enum Status {
        COMPLETED("completed"),
        ERROR("error");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
