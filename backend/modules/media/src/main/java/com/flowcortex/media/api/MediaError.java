package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;

/** The error block of an {@code error} result. */
public record MediaError(@NotNull Code code, @NotNull String message, @NotNull Boolean retryable) {

    /** The closed {@code error.code} enum of the result schema. */
    public enum Code {
        INPUT_REJECTED("input_rejected"),
        PROBE_FAILED("probe_failed"),
        AUDIO_EXTRACTION_FAILED("audio_extraction_failed"),
        CONTRACT_VIOLATION("contract_violation"),
        RUN_TIMEOUT("run_timeout"),
        DURATION_EXCEEDED("duration_exceeded"),
        NO_AUDIO_STREAM("no_audio_stream"),
        NO_SPEECH_DETECTED("no_speech_detected"),
        SEPARATION_FAILED("separation_failed"),
        COMPOSE_FAILED("compose_failed"),
        VOICE_ALIGNMENT_FAILED("voice_alignment_failed");

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
