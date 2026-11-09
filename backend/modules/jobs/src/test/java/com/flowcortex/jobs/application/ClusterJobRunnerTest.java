package com.flowcortex.jobs.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.jobs.infrastructure.ScheduledJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The order of registration and the first pass. */
class ClusterJobRunnerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private final ScheduledJobRepository schedule = mock(ScheduledJobRepository.class);
    private final ClusterJob job = new ClusterJob() {
        @Override
        public String name() {
            return "test.probe";
        }

        @Override
        public Duration interval() {
            return Duration.ofHours(1);
        }

        @Override
        public void run() {
        }
    };
    private final ClusterJobRunner runner = new ClusterJobRunner(new ClusterJobRegistry(List.of(job)), schedule,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aPassBeforeRegistrationTouchesNoScheduleRow() {
        runner.runPass();

        verifyNoInteractions(schedule);
    }

    @Test
    void theFirstPassAfterRegistrationClaimsTheJob() {
        runner.register();
        verify(schedule).register("test.probe", Duration.ofHours(1), NOW);
        verify(schedule, never()).claim(any(), any(), any(), any(), any());

        runner.runPass();

        verify(schedule).claim(eq("test.probe"), any(), any(), eq(NOW), eq(Duration.ofMinutes(10)));
    }
}
