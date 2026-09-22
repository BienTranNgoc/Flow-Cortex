package com.flowcortex.user.domain;

/**
 * Role of an {@link AppUser} (design.md D1). {@code ADMIN} manages the users
 * in their own owner scope via {@code /api/v1/users}; {@code MEMBER} cannot
 * reach that surface at all (design.md D4 authz matrix, R-023).
 */
public enum Role {
    ADMIN,
    MEMBER
}
