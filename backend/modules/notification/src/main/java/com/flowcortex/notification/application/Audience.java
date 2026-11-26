package com.flowcortex.notification.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/**
 * Which of a user's notifications a read or a cap applies to. The audience is carried by the type:
 * {@code admin_} types belong to the admin console bell, every other type to the member bell.
 */
public enum Audience {

    MEMBER("type NOT LIKE 'admin\\_%'"),
    ADMIN("type LIKE 'admin\\_%'");

    private static final String ADMIN_PREFIX = "admin_";

    private final String sqlClause;

    Audience(String sqlClause) {
        this.sqlClause = sqlClause;
    }

    /**
     * @param value {@code member} or {@code admin}; null means {@code member}
     * @throws LocalizedStatusException 400 naming {@code audience}
     */
    public static Audience parse(String value) {
        if (value == null || value.equals("member")) {
            return MEMBER;
        }
        if (value.equals("admin")) {
            return ADMIN;
        }
        throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                new LocalizedMessage("notification.page.invalid", value, "audience"));
    }

    public static Audience of(String type) {
        return type.startsWith(ADMIN_PREFIX) ? ADMIN : MEMBER;
    }

    public boolean holds(String type) {
        return of(type) == this;
    }

    /** A constant predicate over {@code notification.type}, safe to append after {@code AND}. */
    public String sqlClause() {
        return sqlClause;
    }
}
