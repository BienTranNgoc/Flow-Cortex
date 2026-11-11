package com.flowcortex.user.api;

import com.flowcortex.shared.web.LocalizedMessage;

/** A role filter naming a code no {@code role} row holds; answered as a 400 whose {@code errors} names {@code role}. */
public class UnknownRoleException extends RuntimeException {

    public static final String CODE = "role.unknown";

    private final LocalizedMessage detail;

    public UnknownRoleException(String code) {
        super("Unknown role: " + code);
        this.detail = new LocalizedMessage("role.unknown", code);
    }

    public LocalizedMessage getDetail() {
        return detail;
    }
}
