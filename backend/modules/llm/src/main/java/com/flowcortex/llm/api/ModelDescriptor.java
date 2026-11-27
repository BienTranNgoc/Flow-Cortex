package com.flowcortex.llm.api;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The code-defined fact sheet of one text model: what it accepts, how much it holds, how it thinks and
 * where it stands in its lifecycle. {@code shutdownDate}, {@code successorId} and {@code rateCardRef} are
 * absent when the vendor names none; every other field is always set.
 */
@Schema(name = "ModelDescriptor")
public record ModelDescriptor(
        @Schema(requiredMode = RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String providerKey,
        @Schema(requiredMode = RequiredMode.REQUIRED) String family,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Modality> inputModalities,
        @Schema(requiredMode = RequiredMode.REQUIRED) Limits limits,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean functionCalling,
        @Schema(requiredMode = RequiredMode.REQUIRED) StructuredOutput structuredOutput,
        @Schema(requiredMode = RequiredMode.REQUIRED) Thinking thinking,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean temperatureSupported,
        @Schema(requiredMode = RequiredMode.REQUIRED) Status status,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) LocalDate shutdownDate,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String successorId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String rateCardRef) {

    public ModelDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerKey, "providerKey");
        Objects.requireNonNull(family, "family");
        inputModalities = List.copyOf(inputModalities);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(structuredOutput, "structuredOutput");
        Objects.requireNonNull(thinking, "thinking");
        Objects.requireNonNull(status, "status");
    }

    /** Token limits; the output limit never exceeds the context limit in a valid descriptor. */
    @Schema(name = "ModelDescriptorLimits")
    public record Limits(
            @Schema(requiredMode = RequiredMode.REQUIRED) int context,
            @Schema(requiredMode = RequiredMode.REQUIRED) int output) {
    }

    /**
     * The thinking control. {@code values} holds the admissible values (a budget range or the discrete levels,
     * weakest first) and is empty for {@link Knob#NONE}; {@code defaultValue} is one of them, and {@code null}
     * for {@link Knob#NONE}.
     */
    @Schema(name = "ModelDescriptorThinking")
    public record Thinking(
            @Schema(requiredMode = RequiredMode.REQUIRED) Knob knob,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> values,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String defaultValue) {

        public Thinking {
            Objects.requireNonNull(knob, "knob");
            values = List.copyOf(values);
        }
    }

    @Schema(name = "ModelDescriptorModality")
    public enum Modality {
        TEXT("text"),
        IMAGE("image"),
        VIDEO("video"),
        AUDIO("audio"),
        PDF("pdf");

        private final String value;

        Modality(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    @Schema(name = "ModelDescriptorStructuredOutput")
    public enum StructuredOutput {
        RESPONSE_SCHEMA("response_schema"),
        NONE("none");

        private final String value;

        StructuredOutput(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    @Schema(name = "ModelDescriptorKnob")
    public enum Knob {
        BUDGET("budget"),
        LEVEL("level"),
        NONE("none");

        private final String value;

        Knob(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    @Schema(name = "ModelDescriptorStatus")
    public enum Status {
        STABLE("stable"),
        PREVIEW("preview"),
        DEPRECATED("deprecated");

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
