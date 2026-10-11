package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class AccountStatusGuardTest {

    private static final Instant NOW = Instant.now().minusSeconds(60);

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
        assertThatCode(() -> AccountStatusGuard.requireNotLocked(user)).doesNotThrowAnyException();
    }

    @Test
    void requireNotLockedPassesWhenLockoutAlreadyExpired() {
        AppUser user = newUser(UserStatus.ACTIVE);
        // Lock for 1 second at a clearly-past instant; any real "now" is later.
        user.recordFailedLogin(1, java.time.Duration.ofSeconds(1), NOW.minusSeconds(3600));
        assertThatCode(() -> AccountStatusGuard.requireNotLocked(user)).doesNotThrowAnyException();
    }

    @Test
    void requireNotLockedRejectsWhenLockoutIsInTheFuture() {
        AppUser user = newUser(UserStatus.ACTIVE);
        user.recordFailedLogin(1, java.time.Duration.ofMinutes(15), NOW);
        assertThatThrownBy(() -> AccountStatusGuard.requireNotLocked(user))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void requireNotLockedDoesNotMutateUser() {
        AppUser user = newUser(UserStatus.ACTIVE);
        user.recordFailedLogin(1, java.time.Duration.ofMinutes(15), NOW);
        Instant before = user.getLockedUntil();
        try {
            AccountStatusGuard.requireNotLocked(user);
        } catch (AccountLockedException expected) {
            // expected
        }
        assertThat(user.getLockedUntil()).isEqualTo(before);
    }

    private static AppUser newUser(UserStatus status) {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", "hash", "Person", Role.MEMBER, status);
    }
}
