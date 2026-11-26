package com.flowcortex.billing.receipt;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The receipt mail queue. A row is claimed by a conditional update, and every later write of the pass that
 * claimed it only applies while the row still carries that pass's claim time and no outcome, so a pass whose
 * send outlived its lease can change nothing.
 */
@Repository
class ReceiptDeliveryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    ReceiptDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A row due for a send. */
    record Due(UUID transactionId, UUID userId) {
    }

    /** The hold one pass has on a row; {@code claimedAt} is the value the database stored. */
    record Claim(UUID transactionId, int attempts, Timestamp claimedAt) {
    }

    /** Rows without an outcome whose time has come and that nobody holds, oldest first. */
    List<Due> due(Instant now, Instant leaseStart, int limit) {
        return jdbc.query("""
                SELECT transaction_id, user_id FROM payment_receipt_delivery
                WHERE outcome IS NULL AND next_attempt_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseStart)
                ORDER BY created_at, transaction_id LIMIT :limit
                """,
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("leaseStart", Timestamp.from(leaseStart))
                        .addValue("limit", limit),
                (rs, row) -> new Due(rs.getObject("transaction_id", UUID.class), rs.getObject("user_id", UUID.class)));
    }

    /** Takes the row and counts the attempt; empty when another pass holds it or it has its outcome. */
    Optional<Claim> claim(UUID transactionId, Instant now, Instant leaseStart) {
        return jdbc.query("""
                UPDATE payment_receipt_delivery SET claimed_at = :now, attempts = attempts + 1
                WHERE transaction_id = :id AND outcome IS NULL AND (claimed_at IS NULL OR claimed_at < :leaseStart)
                RETURNING attempts, claimed_at
                """,
                new MapSqlParameterSource("id", transactionId).addValue("now", Timestamp.from(now))
                        .addValue("leaseStart", Timestamp.from(leaseStart)),
                (rs, row) -> new Claim(transactionId, rs.getInt("attempts"), rs.getTimestamp("claimed_at")))
                .stream().findFirst();
    }

    /** Every method below answers whether the claim still held and the write was applied. */
    boolean markSent(Claim claim, Instant now) {
        return finish(claim, "sent", Timestamp.from(now));
    }

    boolean markNoAddress(Claim claim) {
        return finish(claim, "no_address", null);
    }

    boolean markFailed(Claim claim) {
        return finish(claim, "failed", null);
    }

    /** Clears the claim and makes the row due again at {@code nextAttemptAt}. */
    boolean release(Claim claim, Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE payment_receipt_delivery SET claimed_at = NULL, next_attempt_at = :next
                WHERE transaction_id = :id AND outcome IS NULL AND claimed_at = :claimedAt
                """,
                new MapSqlParameterSource("id", claim.transactionId()).addValue("claimedAt", claim.claimedAt())
                        .addValue("next", Timestamp.from(nextAttemptAt))) == 1;
    }

    private boolean finish(Claim claim, String outcome, Timestamp sentAt) {
        return jdbc.update("""
                UPDATE payment_receipt_delivery SET outcome = :outcome, sent_at = :sentAt, claimed_at = NULL
                WHERE transaction_id = :id AND outcome IS NULL AND claimed_at = :claimedAt
                """,
                new MapSqlParameterSource("id", claim.transactionId()).addValue("claimedAt", claim.claimedAt())
                        .addValue("outcome", outcome).addValue("sentAt", sentAt)) == 1;
    }
}
