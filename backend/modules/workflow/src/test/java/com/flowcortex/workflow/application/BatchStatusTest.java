package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.application.BatchStatus.Row;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BatchStatusTest {

    private static Set<BatchStatus> statuses(Predicate<BatchStatus> test) {
        return Arrays.stream(BatchStatus.values()).filter(test).collect(Collectors.toCollection(
                () -> EnumSet.noneOf(BatchStatus.class)));
    }

    @Test
    void pauseOnlyFromRunning() {
        assertThat(statuses(BatchStatus::canPause)).containsExactly(BatchStatus.RUNNING);
        assertThat(BatchStatus.PAUSE_FROM).containsExactly(BatchStatus.RUNNING);
    }

    @Test
    void resumeOnlyFromPaused() {
        assertThat(statuses(BatchStatus::canResume)).containsExactly(BatchStatus.PAUSED);
        assertThat(BatchStatus.RESUME_FROM).containsExactly(BatchStatus.PAUSED);
    }

    @Test
    void cancelFromRunningOrPaused() {
        assertThat(statuses(BatchStatus::canCancel)).containsExactlyInAnyOrder(BatchStatus.RUNNING,
                BatchStatus.PAUSED);
        assertThat(BatchStatus.CANCEL_FROM).containsExactlyInAnyOrder(BatchStatus.RUNNING, BatchStatus.PAUSED);
    }

    @Test
    void finishIsCompletedOrCompletedWithErrorsAndACancelledBatchStaysCancelled() {
        assertThat(BatchStatus.RUNNING.finished(0)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(BatchStatus.PAUSED.finished(0)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(BatchStatus.RUNNING.finished(1)).isEqualTo(BatchStatus.COMPLETED_WITH_ERRORS);
        assertThat(BatchStatus.PAUSED.finished(3)).isEqualTo(BatchStatus.COMPLETED_WITH_ERRORS);
        assertThat(BatchStatus.CANCELLED.finished(0)).isEqualTo(BatchStatus.CANCELLED);
        assertThat(BatchStatus.CANCELLED.finished(2)).isEqualTo(BatchStatus.CANCELLED);
        assertThat(BatchStatus.FINISH_FROM).containsExactlyInAnyOrder(BatchStatus.RUNNING, BatchStatus.PAUSED);
    }

    @Test
    void aFinishedBatchIsNotFinishedAgain() {
        assertThatThrownBy(() -> BatchStatus.COMPLETED.finished(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BatchStatus.COMPLETED_WITH_ERRORS.finished(1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theStatusFilterAcceptsOnlyTheStoredNames() {
        assertThat(BatchStatus.parse("completed_with_errors")).contains(BatchStatus.COMPLETED_WITH_ERRORS);
        assertThat(BatchStatus.parse("running")).contains(BatchStatus.RUNNING);
        assertThat(BatchStatus.parse("RUNNING")).isEmpty();
        assertThat(BatchStatus.parse("starting")).isEmpty();
        assertThat(BatchStatus.parse(null)).isEmpty();
    }

    @Test
    void rowFollowsItsRun() {
        assertThat(Row.followingRun("running")).isEqualTo(Row.RUNNING);
        assertThat(Row.followingRun("waiting_approval")).isEqualTo(Row.RUNNING);
        assertThat(Row.followingRun("cancelling")).isEqualTo(Row.RUNNING);
        assertThat(Row.followingRun("completed")).isEqualTo(Row.COMPLETED);
        assertThat(Row.followingRun("error")).isEqualTo(Row.FAILED);
        assertThat(Row.followingRun("cancelled")).isEqualTo(Row.CANCELLED);
        assertThatThrownBy(() -> Row.followingRun("exploded")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startingIsReportedAsPending() {
        assertThat(Row.STARTING.reported()).isEqualTo(Row.PENDING);
        assertThat(Row.PENDING.reported()).isEqualTo(Row.PENDING);
        assertThat(Row.RUNNING.reported()).isEqualTo(Row.RUNNING);
        assertThat(Row.storedFor(Row.PENDING)).containsExactlyInAnyOrder(Row.PENDING, Row.STARTING);
        assertThat(Row.storedFor(Row.FAILED)).containsExactly(Row.FAILED);
        assertThat(Row.parseReported("pending")).contains(Row.PENDING);
        assertThat(Row.parseReported("starting")).isEmpty();
        assertThat(Row.parseReported("bogus")).isEmpty();
    }

    @Test
    void liveRowsAreTheStartingAndRunningOnes() {
        assertThat(Row.LIVE).containsExactlyInAnyOrder(Row.STARTING, Row.RUNNING);
        assertThat(Row.OPEN).containsExactlyInAnyOrder(Row.PENDING, Row.STARTING, Row.RUNNING);
    }

    @Test
    void backToPendingOnlyInARunningOrPausedBatchElseCancelled() {
        assertThat(Row.backToPending(BatchStatus.RUNNING)).isEqualTo(Row.PENDING);
        assertThat(Row.backToPending(BatchStatus.PAUSED)).isEqualTo(Row.PENDING);
        assertThat(Row.backToPending(BatchStatus.CANCELLED)).isEqualTo(Row.CANCELLED);
        assertThat(Row.backToPending(BatchStatus.COMPLETED)).isEqualTo(Row.CANCELLED);
        assertThat(Row.backToPending(BatchStatus.COMPLETED_WITH_ERRORS)).isEqualTo(Row.CANCELLED);
    }

    @Test
    void cancelMovesOnlyPendingRows() {
        for (Row row : Row.values()) {
            Row expected = row == Row.PENDING ? Row.CANCELLED : row;
            assertThat(row.afterBatchCancel()).as(row.name()).isEqualTo(expected);
        }
        assertThat(Row.CANCEL_FROM).containsExactly(Row.PENDING);
    }

    @Test
    void storedNamesRoundTrip() {
        for (BatchStatus status : BatchStatus.values()) {
            assertThat(BatchStatus.of(status.value())).isEqualTo(status);
        }
        for (Row row : Row.values()) {
            assertThat(Row.of(row.value())).isEqualTo(row);
        }
        assertThat(BatchStatus.COMPLETED_WITH_ERRORS.value()).isEqualTo("completed_with_errors");
        assertThat(Row.STARTING.value()).isEqualTo("starting");
    }
}
