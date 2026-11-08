package com.flowcortex.realtime.application;

/**
 * One write to a stream: an event ({@code id}, {@code event}, {@code data}) or, when {@code comment}
 * is set, a comment line that clients ignore.
 */
public record SseFrame(String id, String event, String data, String comment) {

    public static SseFrame event(String id, String event, String data) {
        return new SseFrame(id, event, data, null);
    }

    public static SseFrame ping() {
        return new SseFrame(null, null, null, "ping");
    }

    /** Tells the client its view may be stale and to refetch; it carries no id, so the last real one stands. */
    public static SseFrame resync() {
        return new SseFrame(null, "resync", "{}", null);
    }
}
