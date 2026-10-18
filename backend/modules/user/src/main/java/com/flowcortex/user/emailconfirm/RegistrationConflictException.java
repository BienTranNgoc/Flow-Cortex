package com.flowcortex.user.emailconfirm;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;

/**
 * A registration value another account already holds: 409 whose {@code errors} names the field
 * ({@link RegistrationProblemHandler}).
 */
public class RegistrationConflictException extends ConflictException {

    private final String field;

    private RegistrationConflictException(String field, LocalizedMessage detail) {
        super(detail);
        this.field = field;
    }

    public static RegistrationConflictException emailTaken() {
        return new RegistrationConflictException("email", new LocalizedMessage("auth.register.emailTaken"));
    }

    public static RegistrationConflictException usernameTaken() {
        return new RegistrationConflictException("username", new LocalizedMessage("auth.register.usernameTaken"));
    }

    public String getField() {
        return field;
    }
}
