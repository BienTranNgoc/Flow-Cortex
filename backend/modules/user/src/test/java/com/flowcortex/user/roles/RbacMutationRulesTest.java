package com.flowcortex.user.roles;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.infrastructure.RoleRow;
import org.junit.jupiter.api.Test;

class RbacMutationRulesTest {

    private static final RoleRow OPERATOR = new RoleRow("PLATFORM_OPERATOR", "Platform operator", true, true, false);
    private static final RoleRow ADMIN = new RoleRow("ADMIN", "Admin", true, true, true);
    private static final RoleRow MEMBER = new RoleRow("MEMBER", "Member", true, false, false);
    private static final RoleRow CREATOR = new RoleRow("CREATOR", "Creator", true, false, false);

    @Test
    void creatingAStaffRoleAccountIsGated() {
        assertThat(RbacMutationRules.gatesCreate(OPERATOR)).isTrue();
    }

    @Test
    void creatingASuperAdminAccountIsGated() {
        assertThat(RbacMutationRules.gatesCreate(ADMIN)).isTrue();
    }

    @Test
    void creatingAMemberOrCreatorAccountIsNot() {
        assertThat(RbacMutationRules.gatesCreate(MEMBER)).isFalse();
        assertThat(RbacMutationRules.gatesCreate(CREATOR)).isFalse();
    }

    @Test
    void anUpdateWithoutARoleIsNotGated() {
        assertThat(RbacMutationRules.gatesUpdate("MEMBER", null)).isFalse();
    }

    @Test
    void anUpdateToTheSameRoleIsNotGated() {
        assertThat(RbacMutationRules.gatesUpdate("MEMBER", MEMBER)).isFalse();
    }

    @Test
    void anUpdateToAnotherRoleIsGated() {
        assertThat(RbacMutationRules.gatesUpdate("MEMBER", CREATOR)).isTrue();
    }
}
