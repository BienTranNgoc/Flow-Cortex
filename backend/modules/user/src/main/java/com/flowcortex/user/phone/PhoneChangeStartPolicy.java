package com.flowcortex.user.phone;

import java.util.Optional;

/**
 * Which refusal, if any, stops a number change from starting once the step-up proof was spent. Pure:
 * no I/O, so the order is locked by a unit test.
 */
final class PhoneChangeStartPolicy {

    enum Refusal {
        NOT_VERIFIED,
        SAME_NUMBER
    }

    /** {@code sameNumber}: the requested number equals the member's current one. */
    record StartFacts(boolean hasVerifiedNumber, boolean sameNumber) {
    }

    private PhoneChangeStartPolicy() {
    }

    /** First match wins: having no verified number outranks asking for the number held. */
    static Optional<Refusal> firstRefusal(StartFacts facts) {
        if (!facts.hasVerifiedNumber()) {
            return Optional.of(Refusal.NOT_VERIFIED);
        }
        return facts.sameNumber() ? Optional.of(Refusal.SAME_NUMBER) : Optional.empty();
    }
}
