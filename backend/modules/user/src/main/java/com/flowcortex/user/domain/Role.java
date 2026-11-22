package com.flowcortex.user.domain;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The code of an {@link AppUser}'s role. The seven seeded roles are constants; any other code is a
 * custom role row. Every instance comes from {@link #of}, so two roles of one code are the same
 * object. The staff flag lives only in the {@code role} table, read through the role catalog; the
 * one super admin role is fixed to {@code ADMIN} by a table check.
 */
public final class Role {

    private static final ConcurrentHashMap<String, Role> INTERNED = new ConcurrentHashMap<>();

    public static final Role ADMIN = of("ADMIN");
    public static final Role MEMBER = of("MEMBER");
    public static final Role CREATOR = of("CREATOR");
    public static final Role OPERATION_MANAGER = of("OPERATION_MANAGER");
    public static final Role CUSTOMER_SUPPORT = of("CUSTOMER_SUPPORT");
    public static final Role CONTENT_EDITOR = of("CONTENT_EDITOR");
    public static final Role PLATFORM_OPERATOR = of("PLATFORM_OPERATOR");

    private final String code;

    private Role(String code) {
        this.code = code;
    }

    /** Callers pass a code read from the role table or already found by the role catalog. */
    public static Role of(String code) {
        Objects.requireNonNull(code, "code");
        return INTERNED.computeIfAbsent(code, Role::new);
    }

    public String code() {
        return code;
    }

    public boolean isSuperAdmin() {
        return this == ADMIN;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Role role && code.equals(role.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return code;
    }
}
