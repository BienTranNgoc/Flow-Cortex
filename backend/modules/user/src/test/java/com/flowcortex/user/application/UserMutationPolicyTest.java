package com.flowcortex.user.application;

import static com.flowcortex.shared.security.authz.Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_CHANGE_ROLE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_CREATE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_DELETE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_SUSPEND;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.application.UserMutationPolicy.UserMutation;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.RoleRow;
import org.junit.jupiter.api.Test;

class UserMutationPolicyTest {

    private static final RoleRow MEMBER = new RoleRow("MEMBER", "Member", true, false, false);
    private static final RoleRow CREATOR = new RoleRow("CREATOR", "Creator", true, false, false);
    private static final RoleRow ADMIN = new RoleRow("ADMIN", "Super admin", true, false, true);
    private static final RoleRow CONTENT_EDITOR = new RoleRow("CONTENT_EDITOR", "Content editor", true, true, false);
    private static final RoleRow CUSTOMER_SUPPORT =
            new RoleRow("CUSTOMER_SUPPORT", "Customer support", true, true, false);
    private static final RoleRow PLATFORM_OPERATOR =
            new RoleRow("PLATFORM_OPERATOR", "Platform operator", true, true, false);
    private static final RoleRow CUSTOM_STAFF = new RoleRow("HO_TRO_CAP_2", "Support level 2", false, true, false);

    @Test
    void creatingAMemberNeedsOnlyTheCreateKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(MEMBER)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE);
    }

    @Test
    void creatingAnAdminAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(ADMIN)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void creatingStaffAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(CONTENT_EDITOR)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void creatingACustomStaffRoleNeedsTheSameKeysAsASystemStaffRole() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(CUSTOM_STAFF)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void renamingAMemberNeedsOnlyTheUpdateKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(MEMBER, null, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE);
    }

    @Test
    void changingAMemberToCreatorNeedsTheChangeRoleKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(MEMBER, CREATOR, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_CHANGE_ROLE);
    }

    @Test
    void promotingAMemberToStaffAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(MEMBER, CUSTOMER_SUPPORT, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_CHANGE_ROLE,
                        SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void suspendingAStaffTargetNeedsTheSuspendAndAdminAccountKeys() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(CUSTOMER_SUPPORT, null, UserStatus.ACTIVE, UserStatus.SUSPENDED)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_SUSPEND, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void sendingTheSameRoleOrStatusAddsNoKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(MEMBER, MEMBER, UserStatus.ACTIVE, UserStatus.ACTIVE)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE);
    }

    @Test
    void renamingAStaffTargetNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(CUSTOMER_SUPPORT, null, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void demotingAnAdminNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(ADMIN, MEMBER, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_CHANGE_ROLE,
                        SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void deletingAMemberNeedsOnlyTheDeleteKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.delete(MEMBER)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_DELETE);
    }

    @Test
    void deletingStaffAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.delete(PLATFORM_OPERATOR)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_DELETE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }
}
