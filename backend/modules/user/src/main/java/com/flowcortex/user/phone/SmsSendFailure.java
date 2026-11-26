package com.flowcortex.user.phone;

import java.util.Objects;

/** A message the carrier did not take. The message text never holds a number, a code or an SMS text. */
final class SmsSendFailure extends RuntimeException {

    enum Reason {
        /** Nothing was sent and a later attempt may succeed. */
        UNAVAILABLE,
        /** The carrier refuses this number. */
        NUMBER_REJECTED
    }

    private final Reason reason;

    SmsSendFailure(Reason reason) {
        super("SMS not sent: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
