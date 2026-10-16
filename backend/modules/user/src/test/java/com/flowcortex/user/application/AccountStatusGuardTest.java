package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class AccountStatusGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void requireActivePassesForActiveUser() {
        AppUser user = newUser(UserStatus.ACTIVE);
        assertThatCode(() -> AccountStatusGuard.requireActive(user)).doesNotThrowAnyException();
    }

    @Test
    void requireActiveRejectsSuspendedUser() {
        AppUser user = newUser(UserStatus.SUSPENDED);
        assertThatThrownBy(() -> AccountStatusGuard.requireActive(user))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void requireNotLockedPassesWhenNoLockout() {
        AppUser user = newUser(UserStatus.ACTIVE);
        assertThatCode(() -> AccountStatusGuard.requireNotLocked(user, NOW)).doesNotThrowAnyException();
    }

    @Test
    void requireNotLockedPassesOnceTheLockoutWindowEndedAccordingToTheGivenInstant() {
        AppUser user = newUser(UserStatus.ACTIVE);
        user.recordFailedLogin(1, Duration.ofMinutes(15), NOW);
        assertThatCode(() -> AccountStatusGuard.requireNotLocked(user, NOW.plus(Duration.ofMinutes(15))))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotLockedRejectsWhileTheLockoutWindowIsOpenAccordingToTheGivenInstant() {
        AppUser user = newUser(UserStatus.ACTIVE);
        user.recordFailedLogin(1, Duration.ofMinutes(15), NOW);
        assertThatThrownBy(() -> AccountStatusGuard.requireNotLocked(user, NOW.plus(Duration.ofMinutes(14))))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void requireNotLockedDoesNotMutateUser() {
        AppUser user = newUser(UserStatus.ACTIVE);
        user.recordFailedLogin(1, Duration.ofMinutes(15), NOW);
        Instant before = user.getLockedUntil();
        try {
            AccountStatusGuard.requireNotLocked(user, NOW);
        } catch (AccountLockedException expected) {
            // expected
        }
        assertThat(user.getLockedUntil()).isEqualTo(before);
    }

    @Test
    void canSignInNeedsAnActiveUnlockedAccount() {
        AppUser locked = newUser(UserStatus.ACTIVE);
        locked.recordFailedLogin(1, Duration.ofMinutes(15), NOW);

        assertThat(AccountStatusGuard.canSignIn(newUser(UserStatus.ACTIVE), NOW)).isTrue();
        assertThat(AccountStatusGuard.canSignIn(newUser(UserStatus.SUSPENDED), NOW)).isFalse();
        assertThat(AccountStatusGuard.canSignIn(locked, NOW)).isFalse();
        assertThat(AccountStatusGuard.canSignIn(locked, NOW.plus(Duration.ofMinutes(16)))).isTrue();
    }

    private static AppUser newUser(UserStatus status) {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", "hash", "Person", Role.MEMBER, status);
    }
}
