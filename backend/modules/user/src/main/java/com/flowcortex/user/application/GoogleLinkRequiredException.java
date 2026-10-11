package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;

/**
 * Raised when a Google login matches an existing password account and the
 * configuration is set to {@code require-explicit-link = true}. Mapped to
 * HTTP 409 with body shape per spec §"Trust-existing-account conflict (G5)".
 */
public class GoogleLinkRequiredException extends RuntimeException {

    private final String existingEmail;

    public GoogleLinkRequiredException(String existingEmail) {
        super("An account with this email already exists; confirm with password to link");
        this.existingEmail = existingEmail;
    }

    public String getExistingEmail() {
        return existingEmail;
    }

    /** Build a ProblemDetail-like conflict with the G5 action payload. */
    public ConflictException toConflict() {
        ConflictException ex = new ConflictException(getMessage());
        return ex;
    }
}
