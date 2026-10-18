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

    @Test
    void theFirstConfirmationDropsThePasswordAndItsLockoutAndALaterOneChangesNothing() {
        AppUser user = unconfirmed();
        for (int i = 0; i < 3; i++) {
            user.recordFailedLogin(3, java.time.Duration.ofMinutes(15), NOW);
        }

        assertThat(user.confirmEmailDroppingUnprovenChoices(NOW, null)).isTrue();

        assertThat(user.getEmailConfirmedAt()).isEqualTo(NOW);
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.getPasswordHash()).isEmpty();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        user.setUpFirstPassword("new-hash", "An", "Tran", null);
        assertThat(user.confirmEmailDroppingUnprovenChoices(NOW.plusSeconds(60), "Other")).isFalse();
        assertThat(user.getEmailConfirmedAt()).isEqualTo(NOW);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getName()).isEqualTo("An Tran");
    }

    @Test
    void confirmingDropsTheUsernameAndNamesChosenBeforeTheEmailWasProven() {
        AppUser user = unconfirmed();

        user.confirmEmailDroppingUnprovenChoices(NOW, "  ");

        assertThat(user.getUsername()).isNull();
        assertThat(user.getFirstName()).isNull();
        assertThat(user.getLastName()).isNull();
        assertThat(user.getName()).isEqualTo("person");
    }

    @Test
    void aTakeoverKeepsTheProvidersNameCappedToTheColumn() {
        AppUser named = unconfirmed();
        named.confirmEmailDroppingUnprovenChoices(NOW, " Nguyen Van A ");
        assertThat(named.getName()).isEqualTo("Nguyen Van A");

        AppUser longNamed = unconfirmed();
        longNamed.confirmEmailDroppingUnprovenChoices(NOW, "x".repeat(300));
        assertThat(longNamed.getName()).hasSize(255);
    }

    @Test
    void aSecondRegistrationReplacesEverythingTheFirstChoseWhileUnconfirmed() {
        AppUser user = unconfirmed();

        user.replaceUnconfirmedRegistration("hash-2", "Binh", "Le", null, null);

        assertThat(user.getPasswordHash()).isEqualTo("hash-2");
        assertThat(user.getFirstName()).isEqualTo("Binh");
        assertThat(user.getLastName()).isEqualTo("Le");
        assertThat(user.getName()).isEqualTo("Binh Le");
        assertThat(user.getUsername()).isNull();
        assertThat(user.isEmailConfirmed()).isFalse();
    }

    @Test
    void aConfirmedAccountIsNeverRegisteredAgain() {
        AppUser user = newUser();

        assertThatThrownBy(() -> user.replaceUnconfirmedRegistration("hash-2", "A", "B", null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(user.getPasswordHash()).isEqualTo("hash");
    }

    @Test
    void aFirstPasswordIsSetOnlyOnAnAccountWithoutAndTakesTheChosenIdentity() {
        AppUser oauthOnly = newUser(false);
        oauthOnly.setUpFirstPassword("hash-1", "Binh", "Le", "binh_le");
        assertThat(oauthOnly.hasPassword()).isTrue();
        assertThat(oauthOnly.getPasswordHash()).isEqualTo("hash-1");
        assertThat(oauthOnly.getName()).isEqualTo("Binh Le");
        assertThat(oauthOnly.getFirstName()).isEqualTo("Binh");
        assertThat(oauthOnly.getLastName()).isEqualTo("Le");
        assertThat(oauthOnly.getUsername()).isEqualTo("binh_le");

        assertThatThrownBy(() -> oauthOnly.setUpFirstPassword("hash-2", "A", "B", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(oauthOnly.getPasswordHash()).isEqualTo("hash-1");
        assertThat(oauthOnly.getUsername()).isEqualTo("binh_le");
    }

    @Test
    void aGoogleSignUpIsALinkedPasswordlessMemberNamedLikeATakeover() {
        AppUser named = AppUser.googleSignedUp(UUID.randomUUID(), UUID.randomUUID(), "an.tran@example.com", "sub-1",
                " Tran An ");
        AppUser unnamed = AppUser.googleSignedUp(UUID.randomUUID(), UUID.randomUUID(), "an.tran@example.com", "sub-2",
                null);

        assertThat(named.getName()).isEqualTo("Tran An");
        assertThat(unnamed.getName()).isEqualTo("an.tran");
        assertThat(named.getGoogleSubject()).isEqualTo("sub-1");
        assertThat(named.hasPassword()).isFalse();
        assertThat(named.isEmailConfirmed()).isTrue();
        assertThat(named.getRole()).isEqualTo(Role.MEMBER);
        assertThat(named.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    private static AppUser unconfirmed() {
        return AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "hash-1", "An",
                "Tran", "an_1", null);
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
