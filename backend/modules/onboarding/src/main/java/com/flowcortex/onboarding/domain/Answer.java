package com.flowcortex.onboarding.domain;

/**
 * One answer field of a quiz write: absent keeps the stored value, skipped stores null, a value
 * is stored.
 */
public sealed interface Answer<T> {

    /** The field was not in the request. */
    record Absent<T>() implements Answer<T> {
    }

    /** The field was an explicit JSON null. */
    record Skipped<T>() implements Answer<T> {
    }

    /** The field carried a validated value. */
    record Value<T>(T value) implements Answer<T> {
    }

    static <T> Answer<T> absent() {
        return new Absent<>();
    }

    static <T> Answer<T> skipped() {
        return new Skipped<>();
    }

    static <T> Answer<T> value(T value) {
        return new Value<>(value);
    }
}
