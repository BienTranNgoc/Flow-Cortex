package com.flowcortex.user.pat;

import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Instant;

/**
 * Who may hold personal access tokens: active admins. A login lockout blocks creating and listing,
 * but not requests carrying a token already issued, because anyone who knows an admin's email can
 * trigger a lockout and would otherwise switch that admin's scripts off.
 */
final class PatEligibility {

    private PatEligibility() {
    }

    static boolean eligible(Role role, UserStatus status, Instant lockedUntil, Instant now, PatUse use) {
        if (role != Role.ADMIN || status != UserStatus.ACTIVE) {
            return false;
        }
        return use == PatUse.AUTHENTICATE || lockedUntil == null || !lockedUntil.isAfter(now);
    }
}
