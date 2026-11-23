package com.flowcortex.user.infrastructure;

/** A {@code role} row with the columns the role API reads: description and the keys version. */
public record RoleDetailRow(String code, String name, String description, boolean system, boolean staff,
        boolean superAdmin, int permissionsVersion) {
}
