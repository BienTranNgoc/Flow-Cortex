package com.flowcortex.user.emailconfirm;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * The password was right but the account has not confirmed its email: 403 with code {@value #CODE}
 * ({@link RegistrationProblemHandler}). Thrown only after the password check, so it reveals
 * nothing a wrong password would not.
 */
public class EmailNotConfirmedException extends RuntimeException {

    public static final String CODE = "EMAIL_NOT_CONFIRMED";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("auth.emailNotConfirmed");

    public EmailNotConfirmedException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return DETAIL;
    }
}
