package com.flowcortex.user.phone;

import java.util.Optional;

/**
 * Whether the phone gate holds a caller, from facts read at decision time. Pure: no I/O, so every
 * branch is locked by a unit test.
 */
final class PhoneGateDecision {

    /** {@code staff} and {@code superAdmin} are the flags of the caller's row in the role table. */
    record GateFacts(boolean verified, boolean staff, boolean superAdmin) {
    }

    private PhoneGateDecision() {
    }

    /**
     * Empty facts mean the caller's account row is gone while the access token is still valid; the
     * handler's own 401/404 answers, so the gate does not hold.
     */
    static boolean holds(boolean enabled, Optional<GateFacts> facts) {
        return enabled && facts.filter(known -> !known.verified() && !known.staff() && !known.superAdmin())
                .isPresent();
    }
}
