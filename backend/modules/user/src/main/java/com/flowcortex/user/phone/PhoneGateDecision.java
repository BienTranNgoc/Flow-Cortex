package com.flowcortex.user.phone;

import com.flowcortex.user.domain.Role;
import java.util.Arrays;
import java.util.Optional;

/**
 * Whether the phone gate holds a caller, from facts read at decision time. Pure: no I/O, so every
 * branch is locked by a unit test.
 */
final class PhoneGateDecision {

    /** {@code role} is the stored role string; one the enum does not know reads as an end user. */
    record GateFacts(boolean verified, String role) {
    }

    private PhoneGateDecision() {
    }

    /**
     * Empty facts mean the caller's account row is gone while the access token is still valid; the
     * handler's own 401/404 answers, so the gate does not hold.
     */
    static boolean holds(boolean enabled, Optional<GateFacts> facts) {
        return enabled && facts.filter(known -> !known.verified() && !exemptRole(known.role())).isPresent();
    }

    private static boolean exemptRole(String role) {
        return Arrays.stream(Role.values())
                .filter(candidate -> candidate.name().equals(role))
                .anyMatch(candidate -> candidate.isSuperAdmin() || candidate.isStaff());
    }
}
