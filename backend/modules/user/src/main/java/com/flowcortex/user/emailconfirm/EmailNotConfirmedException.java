package com.flowcortex.user.emailconfirm;

/**
 * The password was right but the account has not confirmed its email: 403 with code {@value #CODE}
 * ({@link RegistrationProblemHandler}). Thrown only after the password check, so it reveals
 * nothing a wrong password would not.
 */
public class EmailNotConfirmedException extends RuntimeException {

    public static final String CODE = "EMAIL_NOT_CONFIRMED";

    public EmailNotConfirmedException() {
        super("Confirm your email before signing in");
    }
}
