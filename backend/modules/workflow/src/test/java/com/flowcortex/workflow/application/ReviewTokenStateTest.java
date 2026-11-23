package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.ReviewTokenState.Expired;
import com.flowcortex.workflow.application.ReviewTokenState.Replay;
import com.flowcortex.workflow.application.ReviewTokenState.ReviewRow;
import com.flowcortex.workflow.application.ReviewTokenState.Usable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewTokenStateTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private static ReviewRow open(UUID user, Instant expiresAt) {
        return new ReviewRow(user, expiresAt, null);
    }

    @Test
    void noRowIsExpired() {
        assertThat(ReviewTokenState.of(Optional.empty(), OWNER, NOW)).isEqualTo(new Expired());
    }

    @Test
    void anotherUsersRowIsExpired() {
        assertThat(ReviewTokenState.of(Optional.of(open(OTHER, NOW.plusSeconds(60))), OWNER, NOW))
                .isEqualTo(new Expired());
    }

    @Test
    void anImportedRowIsReplayForItsOwnerEvenAfterExpiry() {
        UUID workflow = UUID.randomUUID();
        ReviewRow row = new ReviewRow(OWNER, NOW.minusSeconds(3600), workflow);

        assertThat(ReviewTokenState.of(Optional.of(row), OWNER, NOW)).isEqualTo(new Replay(workflow));
    }

    @Test
    void anImportedRowOfAnotherUserIsExpired() {
        ReviewRow row = new ReviewRow(OTHER, NOW.plusSeconds(60), UUID.randomUUID());

        assertThat(ReviewTokenState.of(Optional.of(row), OWNER, NOW)).isEqualTo(new Expired());
    }

    @Test
    void anUnimportedRowAtItsExpiryInstantIsExpired() {
        assertThat(ReviewTokenState.of(Optional.of(open(OWNER, NOW)), OWNER, NOW)).isEqualTo(new Expired());
    }

    @Test
    void anUnimportedRowBeforeExpiryIsUsable() {
        ReviewRow row = open(OWNER, NOW.plusMillis(1));

        assertThat(ReviewTokenState.of(Optional.of(row), OWNER, NOW)).isEqualTo(new Usable(row));
    }
}
