package com.flowcortex.chat.turn;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.quota.ChatQuota;
import com.flowcortex.chat.turn.TurnStore.Stuck;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails turns still {@code streaming} past {@code turn_deadline + sweep-grace} (their instance died or hung)
 * and purges idempotency rows older than a day. The failure is the same conditional write the runner makes,
 * so whichever of the two loses neither refunds nor announces; it runs whatever the chat switch says.
 */
@Component("chatTurnSweeper")
public class TurnSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(TurnSweeper.class);

    private final TurnStore store;
    private final ChatEvents events;
    private final ChatQuota quota;
    private final ChatTelemetry telemetry;
    private final ChatProperties properties;
    private final Clock clock;

    TurnSweeper(TurnStore store, ChatEvents events, ChatQuota quota, ChatTelemetry telemetry,
            ChatProperties properties, Clock clock) {
        this.store = store;
        this.events = events;
        this.quota = quota;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
    }

    /** One pass; the number of turns this pass failed. */
    @Scheduled(fixedDelay = 30_000)
    public int sweep() {
        int failed = 0;
        try {
            Instant now = clock.instant();
            for (Stuck stuck : store.stuck(now.minus(properties.sweepGrace()))) {
                failed += failStuck(stuck);
            }
            int purged = store.purgeIdempotency(now.minus(TurnService.IDEMPOTENCY_WINDOW));
            if (failed > 0 || purged > 0) {
                LOG.info("chat.sweep failed_turns={} purged_idempotency={}", failed, purged);
            }
        } catch (RuntimeException e) {
            LOG.error("chat.sweep outcome=error", e);
        }
        return failed;
    }

    private int failStuck(Stuck stuck) {
        long started = System.nanoTime();
        try {
            if (store.finish(stuck.messageId(), "failed", null, "{\"error\":\"timeout\"}") != 1) {
                return 0;
            }
            if (stuck.chargeDay() != null) {
                quota.refund(stuck.ownerId(), stuck.chargeDay());
            }
            events.updated(stuck.conversationId(), stuck.messageId());
            telemetry.turn("failed", "swept", 0, 0, started);
            return 1;
        } catch (RuntimeException e) {
            LOG.error("chat.sweep outcome=turn_failed", e);
            return 0;
        }
    }
}
