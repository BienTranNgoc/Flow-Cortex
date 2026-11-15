package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.turn.TurnSweeper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The sweeper against the real tables. The scheduled run may fire between a seed and an explicit
 * {@code sweep()}, so every case asserts the resulting state, never the number the call returned.
 */
@ChatIt
class TurnSweeperIT extends ChatItSupport {

    @Autowired
    private TurnSweeper sweeper;

    @Autowired
    private ChatProperties properties;

    @Test
    void aStreamingRowPastItsDeadlinePlusGraceFailsRefundsItsDayOnceAndPublishesOnce() {
        UUID owner = UUID.randomUUID();
        UUID conversation = conversation(owner, "Stuck");
        UUID message = streaming(conversation, 2, pastDeadline(), today());
        setUsage("member:" + owner, today(), 5);
        setUsage("global", today(), 40);

        sweeper.sweep();
        sweeper.sweep();

        assertThat(row(message)).containsEntry("status", "failed");
        assertThat(jdbcTemplate.queryForObject("SELECT payload->>'error' FROM chat_message WHERE id = ?",
                String.class, message)).isEqualTo("timeout");
        assertThat(memberCount(owner)).isEqualTo(4);
        assertThat(globalCount()).isEqualTo(39);
        assertThat(events.updatesOf(message)).isEqualTo(1);
    }

    @Test
    void aRowStillInsideTheGraceAndARowTheRunnerFinishedFirstAreLeftAlone() {
        UUID owner = UUID.randomUUID();
        UUID graceConversation = conversation(owner, "Within grace");
        UUID doneConversation = conversation(owner, "Finished first");
        Instant withinGrace = Instant.now().minus(properties.sweepGrace().dividedBy(2));
        UUID inGrace = streaming(graceConversation, 2, withinGrace, today());
        UUID finished = streaming(doneConversation, 2, pastDeadline(), today());
        jdbcTemplate.update("UPDATE chat_message SET status = 'done', body = 'answer' WHERE id = ?", finished);
        setUsage("member:" + owner, today(), 2);

        sweeper.sweep();

        assertThat(status(inGrace)).isEqualTo("streaming");
        assertThat(row(finished)).containsEntry("status", "done").containsEntry("body", "answer");
        assertThat(memberCount(owner)).isEqualTo(2);
        assertThat(events.updatesOf(inGrace)).isZero();
        assertThat(events.updatesOf(finished)).isZero();
    }

    @Test
    void theRefundGoesToTheDayTheChargeWasMadeAcrossMidnightInHoChiMinh() {
        UUID owner = UUID.randomUUID();
        UUID conversation = conversation(owner, "Overnight");
        LocalDate yesterday = today().minusDays(1);
        UUID message = streaming(conversation, 2, pastDeadline(), yesterday);
        setUsage("member:" + owner, yesterday, 5);
        setUsage("member:" + owner, today(), 3);

        sweeper.sweep();

        assertThat(status(message)).isEqualTo("failed");
        assertThat(usage("member:" + owner, yesterday)).isEqualTo(4);
        assertThat(usage("member:" + owner, today())).isEqualTo(3);
    }

    @Test
    void idempotencyRowsOlderThanADayArePurgedAndNewerOnesStay() {
        UUID owner = UUID.randomUUID();
        insertIdempotency(owner, "old", Instant.now().minus(Duration.ofHours(25)));
        insertIdempotency(owner, "recent", Instant.now().minus(Duration.ofHours(23)));

        sweeper.sweep();

        assertThat(jdbcTemplate.queryForList("SELECT key FROM chat_idempotency WHERE owner_id = ?", String.class,
                owner)).containsExactly("recent");
    }

    @Test
    void stuckTurnsAreFailedEvenWhileTheConsoleSwitchIsOff() {
        UUID owner = UUID.randomUUID();
        UUID conversation = conversation(owner, "Switch off");
        UUID message = streaming(conversation, 2, pastDeadline(), today());
        setUsage("member:" + owner, today(), 1);
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");

        sweeper.sweep();

        assertThat(status(message)).isEqualTo("failed");
        assertThat(memberCount(owner)).isZero();
    }

    private Instant pastDeadline() {
        return Instant.now().minus(properties.sweepGrace()).minusSeconds(60);
    }

    private UUID streaming(UUID conversation, int seq, Instant deadline, LocalDate chargeDay) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, status, "
                + "turn_deadline, charge_day) VALUES (?, ?, ?, 'assistant', 'text', 'partial', 'streaming', ?, ?)",
                id, conversation, seq, Timestamp.from(deadline), chargeDay);
        return id;
    }

    private int usage(String subject, LocalDate day) {
        return jdbcTemplate.queryForObject("SELECT count FROM chat_quota_usage WHERE subject = ? AND day = ?",
                Integer.class, subject, day);
    }

    private void insertIdempotency(UUID owner, String key, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO chat_idempotency (owner_id, key, request_hash, created_at) "
                + "VALUES (?, ?, 'h', ?)", owner, key, Timestamp.from(createdAt));
    }
}
