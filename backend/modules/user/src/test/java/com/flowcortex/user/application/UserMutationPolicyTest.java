package com.flowcortex.user.application;

import static com.flowcortex.shared.security.authz.Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_CHANGE_ROLE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_CREATE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_DELETE;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_SUSPEND;
import static com.flowcortex.shared.security.authz.Permission.USER_ACCOUNT_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.application.UserMutationPolicy.UserMutation;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import org.junit.jupiter.api.Test;

class UserMutationPolicyTest {

    @Test
    void creatingAMemberNeedsOnlyTheCreateKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(Role.MEMBER)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE);
    }

    @Test
    void creatingAnAdminAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(Role.ADMIN)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void creatingStaffAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.create(Role.CONTENT_EDITOR)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_CREATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void renamingAMemberNeedsOnlyTheUpdateKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.MEMBER, null, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE);
    }

    @Test
    void changingAMemberToCreatorNeedsTheChangeRoleKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.MEMBER, Role.CREATOR, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_CHANGE_ROLE);
    }

    @Test
    void promotingAMemberToStaffAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.MEMBER, Role.CUSTOMER_SUPPORT, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_CHANGE_ROLE,
                        SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void suspendingAStaffTargetNeedsTheSuspendAndAdminAccountKeys() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.CUSTOMER_SUPPORT, null, UserStatus.ACTIVE, UserStatus.SUSPENDED)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_SUSPEND, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void sendingTheSameRoleOrStatusAddsNoKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.MEMBER, Role.MEMBER, UserStatus.ACTIVE, UserStatus.ACTIVE)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE);
    }

    @Test
    void renamingAStaffTargetNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.CUSTOMER_SUPPORT, null, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void demotingAnAdminNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(
                UserMutation.update(Role.ADMIN, Role.MEMBER, UserStatus.ACTIVE, null)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_UPDATE, USER_ACCOUNT_CHANGE_ROLE,
                        SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }

    @Test
    void deletingAMemberNeedsOnlyTheDeleteKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.delete(Role.MEMBER)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_DELETE);
    }

    @Test
    void deletingStaffAlsoNeedsTheAdminAccountKey() {
        assertThat(UserMutationPolicy.requiredKeys(UserMutation.delete(Role.PLATFORM_OPERATOR)))
                .containsExactlyInAnyOrder(USER_ACCOUNT_DELETE, SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }
}
