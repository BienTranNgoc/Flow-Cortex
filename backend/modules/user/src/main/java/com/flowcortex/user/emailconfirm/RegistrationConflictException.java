package com.flowcortex.user.emailconfirm;

import com.flowcortex.shared.web.ConflictException;

/**
 * A registration value another account already holds: 409 whose {@code errors} names the field
 * ({@link RegistrationProblemHandler}).
 */
public class RegistrationConflictException extends ConflictException {

    private final String field;

    private RegistrationConflictException(String field, String message) {
        super(message);
        this.field = field;
    }

    public static RegistrationConflictException emailTaken() {
        return new RegistrationConflictException("email", "An account with this email already exists");
    }

    public static RegistrationConflictException usernameTaken() {
        return new RegistrationConflictException("username", "This username is taken");
    }

    public String getField() {
        return field;
    }
}
