package com.flowcortex.user.pat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Instant;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PatEligibilityTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private static boolean eligible(Role role, UserStatus status, Instant lockedUntil, PatUse use) {
        return PatEligibility.eligible(role, status, lockedUntil, NOW, use);
    }

    @Test
    void anActiveUnlockedAdminIsEligibleForBothUses() {
        for (PatUse use : PatUse.values()) {
            assertThat(eligible(Role.ADMIN, UserStatus.ACTIVE, null, use)).isTrue();
        }
    }

    @Test
    void membersAndCreatorsAreNeverEligible() {
        for (PatUse use : PatUse.values()) {
            assertThat(eligible(Role.MEMBER, UserStatus.ACTIVE, null, use)).isFalse();
            assertThat(eligible(Role.CREATOR, UserStatus.ACTIVE, null, use)).isFalse();
        }
    }

    @Test
    void aSuspendedAdminIsNeverEligible() {
        for (PatUse use : PatUse.values()) {
            assertThat(eligible(Role.ADMIN, UserStatus.SUSPENDED, null, use)).isFalse();
        }
    }

    @Test
    void aLoginLockoutBlocksManagingButNotAuthenticating() {
        Instant locked = NOW.plusSeconds(1);

        assertThat(eligible(Role.ADMIN, UserStatus.ACTIVE, locked, PatUse.MANAGE)).isFalse();
        assertThat(eligible(Role.ADMIN, UserStatus.ACTIVE, locked, PatUse.AUTHENTICATE)).isTrue();
    }

    @Test
    void aLockoutThatEndedBlocksNothing() {
        Instant ended = NOW.minusSeconds(1);

        for (PatUse use : PatUse.values()) {
            assertThat(eligible(Role.ADMIN, UserStatus.ACTIVE, ended, use)).isTrue();
        }
    }

    @Test
    void aLockoutEndingExactlyNowBlocksNothing() {
        assertThat(eligible(Role.ADMIN, UserStatus.ACTIVE, NOW, PatUse.MANAGE)).isTrue();
    }

    @Disabled("live when rbac-permission-catalog-enforcement is on the branch")
    @Test
    void anAdminDeactivatedByRbacAndEveryStaffRoleAreNotEligible() {
        // Remap target: role.isSuperAdmin() && ACTIVE && admin_disabled_at IS NULL; whichever of the
        // two changes merges second enables this case.
    }
}
