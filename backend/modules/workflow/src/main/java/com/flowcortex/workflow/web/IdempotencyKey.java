package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.LocalizedMessage;

/** The optional {@code Idempotency-Key} header of a review decision: 1-80 characters once trimmed. */
final class IdempotencyKey {

    static final String HEADER = "Idempotency-Key";
    static final int MAX_CHARS = 80;

    private IdempotencyKey() {
    }

    /** The header trimmed, or null when none was sent; a blank or over-long one is refused. */
    static String parse(String header) {
        if (header == null) {
            return null;
        }
        String key = header.strip();
        if (key.isEmpty() || key.length() > MAX_CHARS) {
            throw new Invalid();
        }
        return key;
    }

    /** A key outside 1-80 characters, rendered as a 400 naming the header by {@link IdempotencyKeyRejectionHandler}. */
    static final class Invalid extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient LocalizedMessage detail = new LocalizedMessage("reviewTask.idempotencyKey.invalid", MAX_CHARS);

        Invalid() {
            super("Idempotency-Key must hold 1-" + MAX_CHARS + " characters", null, false, false);
        }

        LocalizedMessage detail() {
            return detail;
        }
    }
}
