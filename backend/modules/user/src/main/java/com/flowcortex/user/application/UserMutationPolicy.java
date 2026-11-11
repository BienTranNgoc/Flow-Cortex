package com.flowcortex.user.application;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.RoleRow;
import java.util.EnumSet;
import java.util.Set;

/**
 * The keys a change to {@code /api/v1/users} needs, decided from the change alone. Staff and
 * super-admin accounts are managed only by holders of {@code system.admin_account.manage}.
 */
final class UserMutationPolicy {

    private UserMutationPolicy() {
    }

    /** What a request would do to one account; {@code null} requested fields were not sent. */
    record UserMutation(Kind kind, RoleRow currentRole, RoleRow requestedRole, UserStatus currentStatus,
            UserStatus requestedStatus) {

        enum Kind { CREATE, UPDATE, DELETE }

        static UserMutation create(RoleRow role) {
            return new UserMutation(Kind.CREATE, null, role, null, null);
        }

        static UserMutation update(RoleRow currentRole, RoleRow requestedRole, UserStatus currentStatus,
                UserStatus requestedStatus) {
            return new UserMutation(Kind.UPDATE, currentRole, requestedRole, currentStatus, requestedStatus);
        }

        static UserMutation delete(RoleRow currentRole) {
            return new UserMutation(Kind.DELETE, currentRole, null, null, null);
        }

        boolean changesRole() {
            return requestedRole != null && !requestedRole.code().equals(currentRole.code());
        }

        boolean changesStatus() {
            return requestedStatus != null && requestedStatus != currentStatus;
        }
    }

    static Set<Permission> requiredKeys(UserMutation mutation) {
        Set<Permission> keys = EnumSet.noneOf(Permission.class);
        keys.add(baseKey(mutation.kind()));
        if (mutation.kind() == UserMutation.Kind.UPDATE) {
            if (mutation.changesRole()) {
                keys.add(Permission.USER_ACCOUNT_CHANGE_ROLE);
            }
            if (mutation.changesStatus()) {
                keys.add(Permission.USER_ACCOUNT_SUSPEND);
            }
        }
        if (isAdminAccount(mutation.currentRole()) || isAdminAccount(mutation.requestedRole())) {
            keys.add(Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE);
        }
        return keys;
    }

    private static Permission baseKey(UserMutation.Kind kind) {
        return switch (kind) {
            case CREATE -> Permission.USER_ACCOUNT_CREATE;
            case UPDATE -> Permission.USER_ACCOUNT_UPDATE;
            case DELETE -> Permission.USER_ACCOUNT_DELETE;
        };
    }

    private static boolean isAdminAccount(RoleRow role) {
        return role != null && role.isAdminAccount();
    }
}
