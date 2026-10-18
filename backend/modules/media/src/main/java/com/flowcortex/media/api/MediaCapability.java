package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Capabilities the worker executes; the closed {@code capability} enum of the job schema. */
public enum MediaCapability {
    PROBE("probe"),
    EXTRACT_AUDIO("extract_audio"),
    TRANSCRIBE("transcribe"),
    SEPARATE_AUDIO("separate_audio"),
    COMPOSE_DUB("compose_dub");

    private final String value;

    MediaCapability(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** @throws IllegalArgumentException if {@code value} names no capability */
    public static MediaCapability fromValue(String value) {
        return Arrays.stream(values())
                .filter(capability -> capability.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown media capability: " + value));
    }
}
