package com.flowcortex.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppUserTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Test
    void newUserHasPasswordByDefault() {
        AppUser user = newUser();
        assertThat(user.hasPassword()).isTrue();
        assertThat(user.getTokenVersion()).isZero();
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

        user.linkGoogle("google-sub-456");
        assertThat(user.getGoogleSubject()).isEqualTo("google-sub-456");
        assertThat(user.getOauthProvider()).isEqualTo("google");
    }

    @Test
    void unlinkGoogleClearsSubjectAndProvider() {
        AppUser user = newUser();
        user.linkGoogle("sub");
        user.unlinkGoogle();
        assertThat(user.getGoogleSubject()).isNull();
        assertThat(user.getOauthProvider()).isNull();
    }

    @Test
    void bumpTokenVersionIncrementsByOne() {
        AppUser user = newUser();
        user.bumpTokenVersion();
        assertThat(user.getTokenVersion()).isEqualTo(1L);
        user.bumpTokenVersion();
        assertThat(user.getTokenVersion()).isEqualTo(2L);
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
