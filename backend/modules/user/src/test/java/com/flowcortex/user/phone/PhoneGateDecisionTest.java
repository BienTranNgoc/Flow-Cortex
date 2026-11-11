package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.domain.Role;
import com.flowcortex.user.phone.PhoneGateDecision.GateFacts;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PhoneGateDecisionTest {

    @Test
    void aSwitchedOffGateHoldsNoOne() {
        assertThat(PhoneGateDecision.holds(false, facts(false, "MEMBER"))).isFalse();
    }

    @Test
    void aCallerWhoseAccountRowIsGoneIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, Optional.empty())).isFalse();
    }

    @Test
    void aVerifiedMemberIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, facts(true, "MEMBER"))).isFalse();
    }

    @Test
    void anUnverifiedSuperAdminIsNotHeld() {
        assertThat(PhoneGateDecision.holds(true, facts(false, Role.ADMIN.name()))).isFalse();
    }

    @Test
    void anUnverifiedMemberOfEveryStaffRoleIsNotHeld() {
        Role[] staff = Arrays.stream(Role.values()).filter(Role::isStaff).toArray(Role[]::new);

        assertThat(staff).isNotEmpty();
        for (Role role : staff) {
            assertThat(PhoneGateDecision.holds(true, facts(false, role.name()))).as(role.name()).isFalse();
        }
    }

    @Test
    void anUnverifiedMemberIsHeld() {
        assertThat(PhoneGateDecision.holds(true, facts(false, Role.MEMBER.name()))).isTrue();
    }

    @Test
    void anUnverifiedCreatorIsHeld() {
        assertThat(PhoneGateDecision.holds(true, facts(false, Role.CREATOR.name()))).isTrue();
    }

    @Test
    void anUnknownRoleReadsAsNotStaffAndIsHeld() {
        assertThat(PhoneGateDecision.holds(true, facts(false, "SUPERUSER"))).isTrue();
        assertThat(PhoneGateDecision.holds(true, facts(false, null))).isTrue();
    }

    private static Optional<GateFacts> facts(boolean verified, String role) {
        return Optional.of(new GateFacts(verified, role));
    }
}
