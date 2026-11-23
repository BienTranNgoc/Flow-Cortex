package com.flowcortex.user.infrastructure;

/** One row of the {@code role} table: the flags are what decide staff and super-admin treatment. */
public record RoleRow(String code, String name, boolean system, boolean staff, boolean superAdmin) {

    /** A staff or super-admin role: its holders are managed through admin accounts. */
    public boolean isAdminAccount() {
        return staff || superAdmin;
    }
}
