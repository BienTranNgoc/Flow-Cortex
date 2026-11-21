package com.flowcortex.user.domain;

/**
 * Role of an {@link AppUser}. {@code ADMIN} is the super admin: it holds every permission key and
 * manages the users in its own owner scope via {@code /api/v1/users}. The four staff roles hold the
 * keys seeded in {@code role_permission}; {@code MEMBER} and {@code CREATOR} are end users with no
 * admin key. The flags mirror the {@code role} table, which {@code RbacCatalogSeedIT} locks.
 */
public enum Role {
    ADMIN,
    MEMBER,
    CREATOR,
    OPERATION_MANAGER,
    CUSTOMER_SUPPORT,
    CONTENT_EDITOR,
    PLATFORM_OPERATOR;

    public boolean isSuperAdmin() {
        return this == ADMIN;
    }

    public boolean isStaff() {
        return this == OPERATION_MANAGER
                || this == CUSTOMER_SUPPORT
                || this == CONTENT_EDITOR
                || this == PLATFORM_OPERATOR;
    }
}
