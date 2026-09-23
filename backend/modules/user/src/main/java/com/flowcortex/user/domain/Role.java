package com.flowcortex.user.domain;

/**
 * Role of an {@link AppUser}. {@code ADMIN} manages the users in their own
 * owner scope via {@code /api/v1/users}; {@code MEMBER} cannot reach that
 * surface at all.
 */
public enum Role {
    ADMIN,
    MEMBER
}
