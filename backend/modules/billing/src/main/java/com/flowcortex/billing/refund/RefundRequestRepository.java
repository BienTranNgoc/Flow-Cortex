package com.flowcortex.billing.refund;

import com.flowcortex.billing.refund.TurnStanding.CountableRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence of refund requests and the reads of the member's payments they need. Every read is filtered by
 * the owner, so another member's payment is never found. Call the locking methods inside a transaction.
 */
@Repository
class RefundRequestRepository {

    private static final String COLLECTED = "('succeeded', 'partially_refunded', 'refunded', 'disputed')";
    private static final String REQUEST_COLUMNS = "id, code, status, reason, created_at, decided_at, decision_note";

    private static final String RETURNING_REQUEST = " RETURNING " + REQUEST_COLUMNS;

    private static final RowMapper<PaymentRow> PAYMENT = (rs, row) -> new PaymentRow(rs.getObject("id", UUID.class),
            rs.getString("kind"), rs.getString("item_ref"), rs.getLong("amount_vnd"), rs.getString("currency"),
            rs.getString("status"), instant(rs, "succeeded_at"));

    private static final RowMapper<RequestRow> REQUEST = (rs, row) -> new RequestRow(rs.getObject("id", UUID.class),
            rs.getString("code"), rs.getString("status"), rs.getString("reason"), instant(rs, "created_at"),
            instant(rs, "decided_at"), rs.getString("decision_note"));

    private final NamedParameterJdbcTemplate jdbc;

    RefundRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The payment facts the verdict reads; {@code paidAt} is null until the money was collected. */
    record PaymentRow(UUID id, String kind, String itemRef, long amountVnd, String currency, String status,
            Instant paidAt) {
    }

    record RequestRow(UUID id, String code, String status, String reason, Instant createdAt, Instant decidedAt,
            String decisionNote) {
    }

    /** What the requests already made on a payment say about it. */
    record RequestFlags(boolean rejected, boolean approved) {
    }

    Optional<PaymentRow> findPayment(UUID member, UUID transactionId) {
        return jdbc.query("""
                SELECT id, kind, item_ref, amount_vnd, currency, status, succeeded_at FROM payment_transaction
                WHERE id = :id AND user_id = :member
                """, params(member, transactionId), PAYMENT).stream().findFirst();
    }

    /** The payment row locked until the surrounding transaction ends. */
    Optional<PaymentRow> lockPayment(UUID member, UUID transactionId) {
        return jdbc.query("""
                SELECT id, kind, item_ref, amount_vnd, currency, status, succeeded_at FROM payment_transaction
                WHERE id = :id AND user_id = :member FOR UPDATE
                """, params(member, transactionId), PAYMENT).stream().findFirst();
    }

    /** The member's newest plan purchase whose money was collected, newest paid time first. */
    Optional<PaymentRow> latestPlanPurchase(UUID member) {
        return jdbc.query("SELECT id, kind, item_ref, amount_vnd, currency, status, succeeded_at"
                + " FROM payment_transaction WHERE user_id = :member AND kind = 'plan_purchase' AND status IN "
                + COLLECTED + " ORDER BY succeeded_at DESC NULLS LAST, created_at DESC, id DESC LIMIT 1",
                new MapSqlParameterSource("member", member), PAYMENT).stream().findFirst();
    }

    /** Serialises this member's requests until the surrounding transaction ends. */
    void lockMember(UUID member) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "refund_request:" + member));
    }

    /** The member's requests that can hold a turn, created after {@code since}. */
    List<CountableRequest> countableSince(UUID member, Instant since) {
        return jdbc.query("SELECT status, created_at FROM refund_request WHERE user_id = :member"
                + " AND status IN (:statuses) AND created_at > :since",
                new MapSqlParameterSource("member", member).addValue("since", Timestamp.from(since))
                        .addValue("statuses", TurnStanding.COUNTED_STATUSES),
                (rs, row) -> new CountableRequest(rs.getString("status"), instant(rs, "created_at")));
    }

    Optional<RequestRow> latest(UUID member, UUID transactionId) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM refund_request WHERE transaction_id = :id"
                + " AND user_id = :member ORDER BY created_at DESC, id DESC LIMIT 1",
                params(member, transactionId), REQUEST).stream().findFirst();
    }

    Optional<RequestRow> pending(UUID member, UUID transactionId) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM refund_request WHERE transaction_id = :id"
                + " AND user_id = :member AND status = 'pending'", params(member, transactionId), REQUEST)
                .stream().findFirst();
    }

    RequestFlags flags(UUID member, UUID transactionId) {
        return jdbc.queryForObject("""
                SELECT coalesce(bool_or(status = 'rejected'), false) AS rejected,
                       coalesce(bool_or(status IN ('approved', 'auto_approved')), false) AS approved
                FROM refund_request WHERE transaction_id = :id AND user_id = :member
                """, params(member, transactionId),
                (rs, row) -> new RequestFlags(rs.getBoolean("rejected"), rs.getBoolean("approved")));
    }

    /**
     * Inserts a pending request unless the payment already holds one; empty then. The conflict is absorbed in
     * the statement because a failed statement would abort the surrounding PostgreSQL transaction.
     */
    Optional<RequestRow> insertPending(UUID id, UUID member, UUID transactionId, String reason, Instant now) {
        return jdbc.query("""
                INSERT INTO refund_request (id, user_id, transaction_id, reason, status, created_at, updated_at)
                VALUES (:id, :member, :transactionId, :reason, 'pending', :now, :now)
                ON CONFLICT (transaction_id) WHERE status = 'pending' DO NOTHING""" + RETURNING_REQUEST,
                new MapSqlParameterSource("id", id).addValue("member", member)
                        .addValue("transactionId", transactionId).addValue("reason", reason)
                        .addValue("now", Timestamp.from(now)),
                REQUEST).stream().findFirst();
    }

    /** Moves the payment's pending request to cancelled in one conditional statement; empty when none is pending. */
    Optional<RequestRow> cancelPending(UUID member, UUID transactionId, Instant now) {
        return jdbc.query("""
                UPDATE refund_request SET status = 'cancelled', decided_at = :now, decided_by = :member,
                    updated_at = :now
                WHERE transaction_id = :id AND user_id = :member AND status = 'pending'""" + RETURNING_REQUEST,
                params(member, transactionId).addValue("now", Timestamp.from(now)), REQUEST).stream().findFirst();
    }

    private static MapSqlParameterSource params(UUID member, UUID transactionId) {
        return new MapSqlParameterSource("member", member).addValue("id", transactionId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
