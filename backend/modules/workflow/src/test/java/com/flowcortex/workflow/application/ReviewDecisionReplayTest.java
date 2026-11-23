package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.ReviewDecisionReplay.Kind;
import com.flowcortex.workflow.application.ReviewDecisionReplay.Verdict;
import com.flowcortex.workflow.domain.ReviewTaskStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewDecisionReplayTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofDays(1));
    private static final Instant EARLIER = NOW.minus(Duration.ofMinutes(1));

    @Test
    void sameKeySameKindOnADecidedTaskReplays() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, "k-1", Kind.APPROVE, "k-1", NOW,
                LATER)).isEqualTo(Verdict.REPLAY);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.ANSWERS, "k-1", Kind.ANSWERS, "k-1", NOW,
                LATER)).isEqualTo(Verdict.REPLAY);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.REJECTED, Kind.REJECT, "k-1", Kind.REJECT, "k-1", NOW,
                LATER)).isEqualTo(Verdict.REPLAY);
    }

    @Test
    void replayWinsEvenAfterTheTimeLimitBecauseTheDecisionAlreadyHappened() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, "k-1", Kind.APPROVE, "k-1",
                LATER, NOW)).isEqualTo(Verdict.REPLAY);
    }

    @Test
    void otherKeyOrNoKeyOnADecidedTaskConflicts() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, "k-1", Kind.APPROVE, "k-2", NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, "k-1", Kind.APPROVE, null, NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, null, Kind.APPROVE, "k-1", NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, null, Kind.APPROVE, null, NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void sameKeyOtherKindConflicts() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, "k-1", Kind.REJECT, "k-1", NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.APPROVED, Kind.APPROVE, "k-1", Kind.ANSWERS, "k-1", NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.REJECTED, Kind.REJECT, "k-1", Kind.APPROVE, "k-1", NOW,
                LATER)).isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void expiredStatusIsExpired() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.EXPIRED, null, null, Kind.APPROVE, "k-1", NOW, LATER))
                .isEqualTo(Verdict.EXPIRED);
    }

    @Test
    void anExpiredTaskIsNeverTreatedAsDecided() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.EXPIRED, Kind.APPROVE, "k-1", Kind.APPROVE, "k-1", NOW,
                LATER)).isEqualTo(Verdict.EXPIRED);
    }

    @Test
    void pendingAtOrAfterExpiresAtIsExpired() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.PENDING, null, null, Kind.APPROVE, "k-1", NOW, NOW))
                .isEqualTo(Verdict.EXPIRED);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.PENDING, null, null, Kind.REJECT, null, NOW, EARLIER))
                .isEqualTo(Verdict.EXPIRED);
    }

    @Test
    void pendingBeforeExpiresAtApplies() {
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.PENDING, null, null, Kind.APPROVE, "k-1", NOW, LATER))
                .isEqualTo(Verdict.APPLY);
        assertThat(ReviewDecisionReplay.judge(ReviewTaskStatus.PENDING, null, null, Kind.ANSWERS, null, NOW, LATER))
                .isEqualTo(Verdict.APPLY);
    }

    @Test
    void theRecordedKindIsDerivedFromTheStatusAndTheStoredAnswers() {
        assertThat(ReviewDecisionReplay.recordedKind(ReviewTaskStatus.APPROVED, null)).isEqualTo(Kind.APPROVE);
        assertThat(ReviewDecisionReplay.recordedKind(ReviewTaskStatus.APPROVED, "{\"q1\":\"Just me\"}"))
                .isEqualTo(Kind.ANSWERS);
        assertThat(ReviewDecisionReplay.recordedKind(ReviewTaskStatus.REJECTED, null)).isEqualTo(Kind.REJECT);
        assertThat(ReviewDecisionReplay.recordedKind(ReviewTaskStatus.PENDING, null)).isNull();
        assertThat(ReviewDecisionReplay.recordedKind(ReviewTaskStatus.EXPIRED, null)).isNull();
    }
}
