package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.PhoneGateDecision.GateFacts;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PhoneGateDecisionTest {

    @Test
    void aSwitchedOffGateHoldsNoOne() {
        assertThat(PhoneGateDecision.holds(false, endUser(false))).isFalse();
    }

    @Test
    void aCallerWhoseAccountRowIsGoneIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, Optional.empty())).isFalse();
    }

    @Test
    void aVerifiedEndUserIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, endUser(true))).isFalse();
    }

    @Test
    void anUnverifiedSuperAdminIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, Optional.of(new GateFacts(false, false, true)))).isFalse();
    }

    @Test
    void anUnverifiedStaffMemberIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, Optional.of(new GateFacts(false, true, false)))).isFalse();
    }

    @Test
    void anUnverifiedEndUserIsHeld() {
        assertThat(PhoneGateDecision.holds(true, endUser(false))).isTrue();
    }

    private static Optional<GateFacts> endUser(boolean verified) {
        return Optional.of(new GateFacts(verified, false, false));
    }
}
