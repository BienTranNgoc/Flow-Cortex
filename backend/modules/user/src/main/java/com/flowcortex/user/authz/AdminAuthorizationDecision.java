package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import java.util.Collections;
import java.util.Set;

/**
 * The single ordered rule list that decides an admin request; the first rule that matches wins.
 * Pure: facts in, verdict out.
 */
final class AdminAuthorizationDecision {

    private AdminAuthorizationDecision() {
    }

    static Verdict decide(Posture posture, CallerFacts caller) {
        return switch (posture) {
            case Posture.None none -> Verdict.DENY;
            case Posture.Conflict conflict -> Verdict.DENY;
            case Posture.Public open -> Verdict.ALLOW;
            case Posture.AuthenticatedOnly signedIn -> Verdict.ALLOW;
            case Posture.AllOf all -> byKeys(all.keys(), caller, true);
            case Posture.AnyOf any -> byKeys(any.keys(), caller, false);
        };
    }

    private static Verdict byKeys(Set<Permission> required, CallerFacts caller, boolean all) {
        if (required.isEmpty()) {
            return Verdict.DENY;
        }
        if (caller.adminDisabled()) {
            return Verdict.ADMIN_DISABLED;
        }
        if (caller.superAdmin()) {
            return Verdict.ALLOW;
        }
        boolean held = all ? caller.keys().containsAll(required) : !Collections.disjoint(caller.keys(), required);
        return held ? Verdict.ALLOW : Verdict.DENY;
    }
}
