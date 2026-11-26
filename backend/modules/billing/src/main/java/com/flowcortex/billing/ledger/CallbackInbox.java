package com.flowcortex.billing.ledger;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import jakarta.validation.Validator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger's side of the callback inbox: it takes accepted callbacks that nobody has decided and records
 * what was decided. The receiver's own repository is not touched.
 */
@Repository
class CallbackInbox {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** An accepted, undecided callback and how many applications of it have already failed. */
    record Claimed(UUID id, String provider, String eventJson, int applyAttempts) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Validator validator;

    CallbackInbox(NamedParameterJdbcTemplate jdbc, Validator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    /**
     * The oldest accepted callback that is undecided and due, locked until the surrounding transaction ends;
     * a callback another pass holds is passed over. Call it inside a transaction.
     */
    Optional<Claimed> claimNext(Instant now) {
        return jdbc.query("""
                SELECT id, provider, event::text AS event, apply_attempts FROM payment_webhook_event
                WHERE disposition = 'accepted' AND applied_at IS NULL
                    AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                ORDER BY received_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,
                new MapSqlParameterSource("now", Timestamp.from(now)),
                (rs, row) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("provider"),
                        rs.getString("event"), rs.getInt("apply_attempts"))).stream().findFirst();
    }

    /** The stored event, or empty when it is not JSON of the event's shape or breaks the shape's constraints. */
    Optional<PaymentProviderEvent> readEvent(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            PaymentProviderEvent event = MAPPER.readValue(json, PaymentProviderEvent.class);
            return event != null && validator.validate(event).isEmpty() ? Optional.of(event) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /** Records the decision of a callback nobody has decided yet; the row count says whether it was recorded. */
    int markDecided(UUID id, String outcome, String reason, UUID transactionId, Instant now) {
        return jdbc.update("""
                UPDATE payment_webhook_event SET applied_at = :now, application_outcome = :outcome,
                    application_reason = :reason, transaction_id = :transactionId
                WHERE id = :id AND applied_at IS NULL
                """,
                new MapSqlParameterSource("id", id)
                        .addValue("outcome", outcome)
                        .addValue("reason", reason)
                        .addValue("transactionId", transactionId)
                        .addValue("now", Timestamp.from(now)));
    }

    /** Counts one failed application and holds the callback back until {@code nextAttemptAt}. */
    int recordFailedAttempt(UUID id, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE payment_webhook_event SET apply_attempts = apply_attempts + 1, next_attempt_at = :next
                WHERE id = :id AND applied_at IS NULL
                """,
                new MapSqlParameterSource("id", id).addValue("next", Timestamp.from(nextAttemptAt)));
    }
}
