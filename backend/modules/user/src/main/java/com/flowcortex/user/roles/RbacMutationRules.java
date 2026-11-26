package com.flowcortex.user.roles;

import com.flowcortex.user.infrastructure.RoleRow;

/** Which user writes the role-edit kill switch refuses; pure, so the cases are table-testable. */
public final class RbacMutationRules {

    private RbacMutationRules() {
    }

    /** Creating an account in a staff or super-admin role. */
    public static boolean gatesCreate(RoleRow requested) {
        return requested.isAdminAccount();
    }

    /** An update that names a role other than the account's current one; a missing or same role is not a role change. */
    public static boolean gatesUpdate(String currentCode, RoleRow requestedOrNull) {
        return requestedOrNull != null && !requestedOrNull.code().equals(currentCode);
    }
}
