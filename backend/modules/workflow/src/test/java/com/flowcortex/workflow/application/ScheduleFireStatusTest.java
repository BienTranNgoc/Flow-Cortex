package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.application.ScheduleFireStatus.Reported;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduleFireStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final UUID RUN = UUID.randomUUID();
    private static final List<Object> ARGS = List.of(0, 7);

    private static Reported report(String stored, UUID runId, String runStatus, boolean runDeleted, Duration age) {
        return ScheduleFireStatus.report(stored, "INSUFFICIENT_CREDITS", "credits.insufficient", ARGS, runId,
                runStatus, runDeleted, NOW.minus(age), NOW);
    }

    @Test
    void aFoundRunMakesItStartedWhateverWasStored() {
        for (String stored : new String[] {"failed", "starting", "started"}) {
            Reported reported = report(stored, RUN, "running", false, Duration.ofMinutes(10));

            assertThat(reported.status()).as(stored).isEqualTo("started");
            assertThat(reported.runId()).isEqualTo(RUN);
            assertThat(reported.runStatus()).isEqualTo("running");
            assertThat(reported.errorCode()).isNull();
            assertThat(reported.messageKey()).isNull();
            assertThat(reported.messageArgs()).isEmpty();
        }
    }

    @Test
    void storedFailedWithoutRunStaysFailed() {
        Reported reported = report("failed", null, null, false, Duration.ofSeconds(5));

        assertThat(reported.status()).isEqualTo("failed");
        assertThat(reported.runId()).isNull();
        assertThat(reported.runStatus()).isNull();
        assertThat(reported.errorCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(reported.messageKey()).isEqualTo("credits.insufficient");
        assertThat(reported.messageArgs()).containsExactly(0, 7);
    }

    @Test
    void aDeletedRunMakesItStartedWithNoRun() {
        for (String stored : new String[] {"failed", "started"}) {
            Reported reported = report(stored, RUN, "completed", true, Duration.ofMinutes(10));

            assertThat(reported.status()).as(stored).isEqualTo("started");
            assertThat(reported.runId()).isNull();
            assertThat(reported.runStatus()).isNull();
            assertThat(reported.errorCode()).isNull();
            assertThat(reported.messageKey()).isNull();
        }
    }

    @Test
    void startingWithDeletedRunIsStartedWithNoRun() {
        Reported reported = report("starting", RUN, "completed", true, Duration.ofMinutes(3));

        assertThat(reported.status()).isEqualTo("started");
        assertThat(reported.runId()).isNull();
        assertThat(reported.runStatus()).isNull();
        assertThat(reported.errorCode()).isNull();
    }

    @Test
    void storedStartedWithoutRunIsStartedWithNoRun() {
        Reported reported = report("started", null, null, false, Duration.ofDays(40));

        assertThat(reported.status()).isEqualTo("started");
        assertThat(reported.runId()).isNull();
        assertThat(reported.runStatus()).isNull();
        assertThat(reported.errorCode()).isNull();
    }

    @Test
    void startingYoungerThanTwoMinutesIsStarting() {
        Reported reported = report("starting", null, null, false, Duration.ofMinutes(2).minusMillis(1));

        assertThat(reported.status()).isEqualTo("starting");
        assertThat(reported.runId()).isNull();
        assertThat(reported.errorCode()).isNull();
        assertThat(reported.messageKey()).isNull();
    }

    @Test
    void startingTwoMinutesOrOlderIsStartInterrupted() {
        for (Duration age : new Duration[] {Duration.ofMinutes(2), Duration.ofMinutes(3)}) {
            Reported reported = report("starting", null, null, false, age);

            assertThat(reported.status()).as(age.toString()).isEqualTo("failed");
            assertThat(reported.errorCode()).isEqualTo("startInterrupted");
            assertThat(reported.messageKey()).isEqualTo("workflow.schedule.startInterrupted");
            assertThat(reported.messageArgs()).isEmpty();
        }
    }

    @Test
    void aStoredStatusOutsideTheThreeIsRefused() {
        assertThatThrownBy(() -> report("queued", null, null, false, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
