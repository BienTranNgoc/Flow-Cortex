package com.flowcortex.user.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.ExistingLink;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.GoogleNotice;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.ResetLink;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.Skip;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResetRequestPolicyTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void noAccountIsSkippedAsUnknownEmail() {
        assertThat(ResetRequestPolicy.decide(Optional.empty())).isEqualTo(new Skip("unknown_email"));
    }

    @Test
    void aSuspendedAccountIsSkippedAsInactive() {
        AppUser suspended = new AppUser(UUID.randomUUID(), OWNER, "s@example.com", "hash", "S", Role.MEMBER,
                UserStatus.SUSPENDED);

        assertThat(ResetRequestPolicy.decide(Optional.of(suspended))).isEqualTo(new Skip("inactive"));
    }

    @Test
    void anUnconfirmedAccountGetsItsExistingConfirmationLink() {
        AppUser unconfirmed = AppUser.selfRegistered(UUID.randomUUID(), OWNER, "u@example.com", "hash", "U", "Ser",
                "user", null);

        assertThat(ResetRequestPolicy.decide(Optional.of(unconfirmed)))
                .isEqualTo(new ExistingLink(unconfirmed.getId()));
    }

    @Test
    void aConfirmedAccountWithAPasswordGetsAResetLink() {
        AppUser withPassword = new AppUser(UUID.randomUUID(), OWNER, "an@example.com", "hash", "An", Role.MEMBER,
                UserStatus.ACTIVE);

        assertThat(ResetRequestPolicy.decide(Optional.of(withPassword)))
                .isEqualTo(new ResetLink(withPassword.getId()));
    }

    @Test
    void aGoogleAccountThatAlsoHasAPasswordGetsAResetLink() {
        AppUser googleWithPassword = new AppUser(UUID.randomUUID(), OWNER, "gp@example.com", "hash", "G",
                Role.MEMBER, UserStatus.ACTIVE);
        googleWithPassword.linkGoogle("google-subject-1");

        assertThat(ResetRequestPolicy.decide(Optional.of(googleWithPassword)))
                .isEqualTo(new ResetLink(googleWithPassword.getId()));
    }

    @Test
    void aGoogleAccountWithoutAPasswordGetsTheGoogleNotice() {
        AppUser google = AppUser.googleSignedUp(UUID.randomUUID(), OWNER, "g@example.com", "google-subject-2", "G");

        assertThat(ResetRequestPolicy.decide(Optional.of(google))).isEqualTo(new GoogleNotice(google.getId()));
    }

    @Test
    void aConfirmedAccountWithoutPasswordOrGoogleGetsItsExistingSetPasswordLink() {
        AppUser noPassword = new AppUser(UUID.randomUUID(), OWNER, "c@example.com", "", "C", Role.MEMBER,
                UserStatus.ACTIVE, false);

        assertThat(ResetRequestPolicy.decide(Optional.of(noPassword)))
                .isEqualTo(new ExistingLink(noPassword.getId()));
    }
}
