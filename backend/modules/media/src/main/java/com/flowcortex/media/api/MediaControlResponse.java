package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Control check response, shape of {@code contracts/media-control-response.schema.json}, telling a
 * video worker whether its in-flight delivery may continue, must cancel because its sibling clip
 * failed, or is stale and should be discarded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaControlResponse(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull UUID runId,
        @NotNull UUID jobId,
        @NotNull @Min(1) Integer attempt,
        @NotNull Decision decision) {

    /** The closed {@code decision} enum of the control-response schema. */
    public enum Decision {
        CONTINUE("continue"),
        CANCEL_SIBLING_FAILED("cancel_sibling_failed"),
        DISCARD_STALE("discard_stale");

        private final String value;

        Decision(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
