package com.flowcortex.user.application;

import java.util.Locale;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

/** Names the database constraint behind a write failure, so callers answer by constraint and never by message text. */
public final class ConstraintViolations {

    /** The foreign key from {@code app_user.role} to {@code role.code}. */
    public static final String APP_USER_ROLE_FK = "fk_app_user_role";

    private static final int MAX_CAUSE_DEPTH = 10;

    private ConstraintViolations() {
    }

    /** The lower-case name of the first violated constraint in the cause chain, when the driver reported one. */
    public static Optional<String> nameOf(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return Optional.of(violation.getConstraintName().toLowerCase(Locale.ROOT));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    public static boolean isRoleForeignKey(Throwable error) {
        return nameOf(error).filter(APP_USER_ROLE_FK::equals).isPresent();
    }
}
