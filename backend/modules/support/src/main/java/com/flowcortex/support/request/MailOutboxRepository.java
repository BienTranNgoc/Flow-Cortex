package com.flowcortex.support.request;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reply notifications waiting to be mailed: six attempts in all, each claimed before it is sent. */
@Repository
class MailOutboxRepository {

    static final int MAX_ATTEMPTS = 6;

    private final NamedParameterJdbcTemplate jdbc;

    MailOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param locale the language the member filed the request in */
    record OutboxRow(UUID id, UUID recipientUserId, long requestNumber, String locale, int attempts) {
    }

    void insert(UUID requestId, UUID messageId, UUID recipientUserId, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("INSERT INTO support_mail_outbox (id, request_id, message_id, recipient_user_id, attempts, "
                + "next_attempt_at, created_at, updated_at) VALUES (:id, :request, :message, :recipient, 0, :now, "
                + ":now, :now)", new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("request", requestId).addValue("message", messageId)
                .addValue("recipient", recipientUserId).addValue("now", at));
    }

    List<UUID> due(Instant now, int limit) {
        return jdbc.queryForList("SELECT id FROM support_mail_outbox WHERE sent_at IS NULL AND attempts < :max "
                + "AND next_attempt_at <= :now ORDER BY next_attempt_at LIMIT :limit", new MapSqlParameterSource()
                .addValue("max", MAX_ATTEMPTS).addValue("now", Timestamp.from(now)).addValue("limit", limit),
                UUID.class);
    }

    /**
     * Pushes the row's next attempt to {@code leaseUntil} if it is still due, so a send slower than one job tick is
     * never repeated by the next tick.
     */
    Optional<OutboxRow> claim(UUID id, Instant now, Instant leaseUntil) {
        int claimed = jdbc.update("UPDATE support_mail_outbox SET next_attempt_at = :lease, updated_at = :now "
                + "WHERE id = :id AND sent_at IS NULL AND attempts < :max AND next_attempt_at <= :now",
                new MapSqlParameterSource("id", id).addValue("lease", Timestamp.from(leaseUntil))
                        .addValue("now", Timestamp.from(now)).addValue("max", MAX_ATTEMPTS));
        if (claimed == 0) {
            return Optional.empty();
        }
        return jdbc.query("SELECT o.id, o.recipient_user_id, o.attempts, r.number, r.locale "
                + "FROM support_mail_outbox o JOIN support_request r ON r.id = o.request_id WHERE o.id = :id",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getObject("recipient_user_id", UUID.class),
                        rs.getLong("number"), rs.getString("locale"), rs.getInt("attempts"))).stream().findFirst();
    }

    void markSent(UUID id, int attempts, Instant now) {
        jdbc.update("UPDATE support_mail_outbox SET sent_at = :now, attempts = :attempts, last_error = NULL, "
                + "updated_at = :now WHERE id = :id", new MapSqlParameterSource("id", id)
                .addValue("attempts", attempts).addValue("now", Timestamp.from(now)));
    }

    /** @param nextAttemptAt when to try again; with {@code attempts} at the maximum the row is never due again */
    void markFailed(UUID id, int attempts, Instant nextAttemptAt, String error, Instant now) {
        jdbc.update("UPDATE support_mail_outbox SET attempts = :attempts, next_attempt_at = :next, "
                + "last_error = :error, updated_at = :now WHERE id = :id", new MapSqlParameterSource("id", id)
                .addValue("attempts", attempts).addValue("next", Timestamp.from(nextAttemptAt))
                .addValue("error", error).addValue("now", Timestamp.from(now)));
    }
}
