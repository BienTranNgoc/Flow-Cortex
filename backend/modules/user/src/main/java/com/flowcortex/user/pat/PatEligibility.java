package com.flowcortex.user.pat;

import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Instant;

/**
 * Who may hold personal access tokens: active super-admins whose admin access is not deactivated.
 * A login lockout blocks creating and listing, but not requests carrying a token already issued,
 * because anyone who knows an admin's email can trigger a lockout and would otherwise switch that
 * admin's scripts off.
 */
final class PatEligibility {

    private PatEligibility() {
    }

    static boolean eligible(Role role, UserStatus status, boolean adminDisabled, Instant lockedUntil, Instant now,
            PatUse use) {
        if (!role.isSuperAdmin() || status != UserStatus.ACTIVE || adminDisabled) {
            return false;
        }
        return use == PatUse.AUTHENTICATE || lockedUntil == null || !lockedUntil.isAfter(now);
    }
}
