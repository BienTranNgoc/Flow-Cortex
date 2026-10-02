package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;

/** The error block of an {@code error} result. */
public record MediaError(@NotNull Code code, @NotNull String message, @NotNull Boolean retryable) {

    /** The closed {@code error.code} enum of the result schema. */
    public enum Code {
        INPUT_REJECTED("input_rejected"),
        PROBE_FAILED("probe_failed"),
        CONTRACT_VIOLATION("contract_violation"),
        RUN_TIMEOUT("run_timeout");

        private final String value;

        Code(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
