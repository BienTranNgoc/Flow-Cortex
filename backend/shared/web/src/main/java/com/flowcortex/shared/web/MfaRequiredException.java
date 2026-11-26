package com.flowcortex.shared.web;

/**
 * Thrown when a console account whose session has not passed a second factor calls an admin route
 * while the requirement is on. Mapped by {@link GlobalExceptionHandler} to a 403 problem of type
 * {@code mfa-required}; {@code enrolled} tells the console whether to ask for a code or start the setup.
 */
public class MfaRequiredException extends RuntimeException {

    private final boolean enrolled;

    public MfaRequiredException(boolean enrolled) {
        super("A second factor is required");
        this.enrolled = enrolled;
    }

    /** The account already has TOTP on; false when it still has to set it up. */
    public boolean isEnrolled() {
        return enrolled;
    }
}
