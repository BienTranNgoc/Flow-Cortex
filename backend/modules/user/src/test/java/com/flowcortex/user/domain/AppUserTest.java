package com.flowcortex.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppUserTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Test
    void newUserHasPasswordByDefault() {
        AppUser user = newUser();
        assertThat(user.hasPassword()).isTrue();
    }

    @Test
    void oauthOnlyUserHasHasPasswordFalse() {
        AppUser user = newUser(false);
        assertThat(user.hasPassword()).isFalse();
    }

    @Test
    void linkGoogleSetsSubjectAndProviderOnce() {
        AppUser user = newUser();
        user.linkGoogle("google-sub-123");
        assertThat(user.getGoogleSubject()).isEqualTo("google-sub-123");
        assertThat(user.getOauthProvider()).isEqualTo("google");

        user.linkGoogle("google-sub-123");
        assertThat(user.getGoogleSubject()).isEqualTo("google-sub-123");
    }

    @Test
    void linkGoogleNeverReplacesAnotherSubject() {
        AppUser user = newUser();
        user.linkGoogle("google-sub-123");

        assertThatThrownBy(() -> user.linkGoogle("google-sub-456"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(user.getGoogleSubject()).isEqualTo("google-sub-123");
    }

    @Test
    void recordFailedLoginClampsAtShortMax() {
        AppUser user = newUser();
        for (int i = 0; i < 10; i++) {
            user.recordFailedLogin(3, java.time.Duration.ofMinutes(15), NOW);
        }
        assertThat(user.getFailedLoginAttempts()).isLessThanOrEqualTo((short) (Short.MAX_VALUE));
    }

    @Test
    void recordFailedLoginLocksAtMaxAttempts() {
        AppUser user = newUser();
        user.recordFailedLogin(3, java.time.Duration.ofMinutes(15), NOW);
        user.recordFailedLogin(3, java.time.Duration.ofMinutes(15), NOW);
        user.recordFailedLogin(3, java.time.Duration.ofMinutes(15), NOW);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(java.time.Duration.ofMinutes(15)));
    }

    @Test
    void aFailureAfterTheLockoutWindowEndedStartsANewCountAndCanLockAgain() {
        AppUser user = newUser();
        java.time.Duration window = java.time.Duration.ofMinutes(15);
        user.recordFailedLogin(2, window, NOW);
        user.recordFailedLogin(2, window, NOW);
        Instant afterWindow = NOW.plus(window);

        user.recordFailedLogin(2, window, afterWindow);

        assertThat(user.getFailedLoginAttempts()).isEqualTo((short) 1);
        assertThat(user.getLockedUntil()).isNull();
        user.recordFailedLogin(2, window, afterWindow);
        assertThat(user.getLockedUntil()).isEqualTo(afterWindow.plus(window));
    }

    @Test
    void resetFailedLoginAttemptsClearsCounterAndLock() {
        AppUser user = newUser();
        user.recordFailedLogin(3, java.time.Duration.ofMinutes(15), NOW);
        user.resetFailedLoginAttempts(NOW);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    private static AppUser newUser() {
        return newUser(true);
    }

    private static AppUser newUser(boolean hasPassword) {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", hasPassword ? "hash" : "", "Person", Role.MEMBER,
                UserStatus.ACTIVE, hasPassword);
    }
}
