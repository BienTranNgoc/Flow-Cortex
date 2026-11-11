package com.flowcortex.user.stepup;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.StepUpPurpose;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepUpVerdictTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final ConsumedProof ROW =
            new ConsumedProof(USER, StepUpPurpose.PAT_CREATE.name(), NOW.plusSeconds(1));

    @Test
    void refusesWhenNoRowWasSpent() {
        assertThat(StepUpVerdict.of(null, USER, StepUpPurpose.PAT_CREATE, NOW)).isEqualTo(StepUpVerdict.REFUSED);
    }

    @Test
    void refusesAnotherUsersProof() {
        assertThat(StepUpVerdict.of(ROW, UUID.randomUUID(), StepUpPurpose.PAT_CREATE, NOW))
                .isEqualTo(StepUpVerdict.REFUSED);
    }

    @Test
    void refusesAProofMintedForAnotherPurpose() {
        ConsumedProof other = new ConsumedProof(USER, "OTHER", NOW.plusSeconds(1));

        assertThat(StepUpVerdict.of(other, USER, StepUpPurpose.PAT_CREATE, NOW)).isEqualTo(StepUpVerdict.REFUSED);
    }

    @Test
    void refusesAtTheExpiryInstant() {
        assertThat(StepUpVerdict.of(ROW, USER, StepUpPurpose.PAT_CREATE, NOW.plusSeconds(1)))
                .isEqualTo(StepUpVerdict.REFUSED);
    }

    @Test
    void acceptsOneSecondBeforeExpiry() {
        assertThat(StepUpVerdict.of(ROW, USER, StepUpPurpose.PAT_CREATE, NOW)).isEqualTo(StepUpVerdict.ACCEPTED);
    }

    @Test
    void acceptsWhenUserPurposeAndExpiryAllMatch() {
        ConsumedProof fresh = new ConsumedProof(USER, StepUpPurpose.PAT_CREATE.name(), NOW.plusSeconds(300));

        assertThat(StepUpVerdict.of(fresh, USER, StepUpPurpose.PAT_CREATE, NOW)).isEqualTo(StepUpVerdict.ACCEPTED);
    }
}
