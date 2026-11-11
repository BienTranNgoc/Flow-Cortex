package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import java.util.EnumSet;
import java.util.Set;

/**
 * What the decision needs to know about the caller, read from the database on the request.
 *
 * @param superAdmin    the role holds every key by its flag
 * @param adminDisabled the account is a staff or super-admin account whose console access is deactivated
 * @param keys          the role's keys for a staff role; empty for a super admin and for an end user
 */
public record CallerFacts(boolean superAdmin, boolean adminDisabled, Set<Permission> keys) {

    public CallerFacts {
        keys = Set.copyOf(keys);
    }

    /** The keys the caller can use right now: every key for a super admin, none once deactivated. */
    public Set<Permission> effectiveKeys() {
        if (adminDisabled) {
            return Set.of();
        }
        return superAdmin ? EnumSet.allOf(Permission.class) : keys;
    }
}
