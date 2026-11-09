package com.flowcortex.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.quota.ChatQuota;
import com.flowcortex.chat.turn.TurnStore.Stuck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnSweeperTest {

    private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(30);

    private final TurnStore store = mock(TurnStore.class);
    private final ChatEvents events = mock(ChatEvents.class);
    private final ChatQuota quota = mock(ChatQuota.class);
    private final ChatTelemetry telemetry = mock(ChatTelemetry.class);
    private final ChatProperties properties = new ChatProperties(true, "model",
            new ChatProperties.Quota(100, 5000, 10), Duration.ofSeconds(90), GRACE, 4, 8, 32);
    private final TurnSweeper sweeper = new TurnSweeper(store, events, quota, telemetry, properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Stuck stuck(LocalDate chargeDay) {
        return new Stuck(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), chargeDay);
    }

    @Test
    void aStuckTurnIsFailedRefundedAndAnnouncedOnce() {
        Stuck turn = stuck(LocalDate.of(2026, 11, 8));
        when(store.stuck(NOW.minus(GRACE))).thenReturn(List.of(turn));
        when(store.finish(eq(turn.messageId()), eq("failed"), any(), any())).thenReturn(1);

        assertThat(sweeper.sweep()).isEqualTo(1);

        verify(store).finish(turn.messageId(), "failed", null, "{\"error\":\"timeout\"}");
        verify(quota).refund(turn.ownerId(), turn.chargeDay());
        verify(events).updated(turn.conversationId(), turn.messageId());
        verify(telemetry).turn(eq("failed"), eq("swept"), anyInt(), anyInt(), anyLong());
        verify(store).purgeIdempotency(NOW.minus(TurnService.IDEMPOTENCY_WINDOW));
    }

    @Test
    void aTurnTheRunnerAlreadyEndedIsNeitherRefundedNorAnnounced() {
        Stuck turn = stuck(LocalDate.of(2026, 11, 8));
        when(store.stuck(any())).thenReturn(List.of(turn));
        when(store.finish(any(), any(), any(), any())).thenReturn(0);

        assertThat(sweeper.sweep()).isZero();

        verify(quota, never()).refund(any(), any());
        verify(events, never()).updated(any(), any());
    }

    @Test
    void aTurnThatWasNeverChargedIsFailedWithoutARefund() {
        Stuck turn = stuck(null);
        when(store.stuck(any())).thenReturn(List.of(turn));
        when(store.finish(any(), any(), any(), any())).thenReturn(1);

        assertThat(sweeper.sweep()).isEqualTo(1);

        verify(quota, never()).refund(any(), any());
        verify(events).updated(turn.conversationId(), turn.messageId());
    }

    @Test
    void aFailureOnOneTurnDoesNotStopTheOthers() {
        Stuck broken = stuck(LocalDate.of(2026, 11, 8));
        Stuck fine = stuck(null);
        when(store.stuck(any())).thenReturn(List.of(broken, fine));
        when(store.finish(eq(broken.messageId()), any(), any(), any())).thenThrow(new IllegalStateException("db"));
        when(store.finish(eq(fine.messageId()), any(), any(), any())).thenReturn(1);

        assertThat(sweeper.sweep()).isEqualTo(1);

        verify(events).updated(fine.conversationId(), fine.messageId());
    }

    @Test
    void aFailingPassIsLoggedAndReportsNoFailedTurns() {
        when(store.stuck(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(sweeper.sweep()).isZero();
    }
}
