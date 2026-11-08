package com.flowcortex.support.request;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** A request's lifecycle status; {@link #value()} is what the database and the API carry. */
enum RequestStatus {
    NEW, IN_PROGRESS, WAITING_MEMBER, RESOLVED, CLOSED;

    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    static RequestStatus of(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("Unknown request status: " + value));
    }

    static Optional<RequestStatus> parse(String value) {
        return Arrays.stream(values()).filter(status -> status.value().equals(value)).findFirst();
    }
}
