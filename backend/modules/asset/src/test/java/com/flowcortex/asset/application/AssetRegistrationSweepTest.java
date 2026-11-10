package com.flowcortex.asset.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RegistrationStatus;
import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssetRegistrationSweepTest {

    private static final Instant START = Instant.parse("2026-11-11T10:00:00Z");

    private final AssetRegistrationStore queue = mock(AssetRegistrationStore.class);
    private final AssetRegistry registry = mock(AssetRegistry.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final AssetRegistrationSweep sweep = new AssetRegistrationSweep(queue, registry,
            Duration.ofSeconds(120), clock);

    @Test
    void aPassProcessesEveryClaimedRowAndReleasesNone() {
        List<RegistrationRow> due = rows(3);
        when(queue.claimDue(AssetRegistrationSweep.DUE_BATCH)).thenReturn(due);

        sweep.run();

        due.forEach(row -> verify(registry).process(row));
        verify(queue, never()).release(any());
    }

    @Test
    void aStoreOutageStopsThePassAfterThatRowAndReleasesTheUntriedRows() {
        List<RegistrationRow> due = rows(5);
        when(queue.claimDue(AssetRegistrationSweep.DUE_BATCH)).thenReturn(due);
        when(registry.process(due.get(1))).thenReturn(true);

        sweep.run();

        verify(registry).process(due.get(0));
        verify(registry).process(due.get(1));
        verify(registry, times(2)).process(any());
        assertThat(released()).containsExactlyElementsOf(due.subList(2, 5));
        verify(queue).cleanup(AssetRegistrationSweep.CLEANUP_BATCH);
    }

    @Test
    void noRowIsStartedOncePassBudgetIsSpentAndTheRestIsReleased() {
        List<RegistrationRow> due = rows(6);
        when(queue.claimDue(AssetRegistrationSweep.DUE_BATCH)).thenReturn(due);
        doAnswer(call -> {
            now.updateAndGet(t -> t.plus(Duration.ofMinutes(1)));
            return false;
        }).when(registry).process(any());

        sweep.run();

        assertThat(AssetRegistrationSweep.PASS_BUDGET).isEqualTo(Duration.ofMinutes(4));
        verify(registry, times(4)).process(any());
        assertThat(released()).containsExactlyElementsOf(due.subList(4, 6));
    }

    @Test
    void aFailedReleaseDoesNotFailThePassNorSkipTheCleanup() {
        List<RegistrationRow> due = rows(2);
        when(queue.claimDue(AssetRegistrationSweep.DUE_BATCH)).thenReturn(due);
        when(registry.process(due.get(0))).thenReturn(true);
        when(queue.release(any())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(sweep::run).doesNotThrowAnyException();

        verify(queue).cleanup(AssetRegistrationSweep.CLEANUP_BATCH);
    }

    private List<RegistrationRow> released() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistrationRow>> untried = ArgumentCaptor.forClass(List.class);
        verify(queue).release(untried.capture());
        return untried.getValue();
    }

    private static List<RegistrationRow> rows(int count) {
        List<RegistrationRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new RegistrationRow(UUID.randomUUID(), SourceType.RUN_INPUTS, UUID.randomUUID(),
                    UUID.randomUUID(), RegistrationStatus.PENDING, 0, UUID.randomUUID()));
        }
        return rows;
    }
}
