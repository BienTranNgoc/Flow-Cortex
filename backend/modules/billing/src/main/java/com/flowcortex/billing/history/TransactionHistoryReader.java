package com.flowcortex.billing.history;

import com.flowcortex.billing.ledger.MoneyStatus;
import com.flowcortex.billing.ledger.Timeline;
import com.flowcortex.billing.ledger.Timeline.Composed;
import com.flowcortex.billing.ledger.Timeline.HistoryEntry;
import com.flowcortex.billing.ledger.Timeline.TimelineEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** A member's own payments. Every read is filtered by the caller, so another member's payment is never found. */
@Repository
public class TransactionHistoryReader {

    private static final String SETTLED = "status IN ('succeeded', 'partially_refunded', 'refunded')";

    private final NamedParameterJdbcTemplate jdbc;

    TransactionHistoryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One payment of the list. */
    public record Row(UUID id, String kind, String description, long amountVnd, String status, Instant openedAt,
            Instant succeededAt) {
    }

    /** One page of rows and the count of every payment the filter matches. */
    public record Page(List<Row> rows, long total) {
    }

    /** One payment with the facts the provider reported and the timeline of what happened to it. */
    public record Detail(UUID id, String kind, String description, long amountVnd, String status, Instant openedAt,
            Instant succeededAt, String orderRef, String provider, String instrument, String providerPayType,
            String providerTransactionId, String outcomeCode, List<TimelineEvent> timeline) {
    }

    /** Newest first by opening time, then id; two queries on {@code idx_payment_transaction_user_created}. */
    public Page page(UUID user, HistoryQuery query) {
        String filter = query.settled() ? " AND " + SETTLED : "";
        MapSqlParameterSource params = new MapSqlParameterSource("user", user)
                .addValue("size", query.size())
                .addValue("offset", query.offset());
        List<Row> rows = jdbc.query("SELECT id, kind, description, amount_vnd, status, created_at, succeeded_at "
                + "FROM payment_transaction WHERE user_id = :user" + filter
                + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset", params, (rs, i) -> row(rs));
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM payment_transaction WHERE user_id = :user" + filter, params, Long.class);
        return new Page(rows, total == null ? 0 : total);
    }

    public Optional<Detail> findOwn(UUID user, UUID transactionId) {
        return find("user_id = :user AND id = :key", user, transactionId);
    }

    public Optional<Detail> findOwnByOrder(UUID user, String orderRef) {
        return find("user_id = :user AND order_ref = :key", user, orderRef);
    }

    private Optional<Detail> find(String condition, UUID user, Object key) {
        MapSqlParameterSource params = new MapSqlParameterSource("user", user).addValue("key", key);
        Optional<Detail> payment = jdbc.query("SELECT id, kind, description, amount_vnd, status, created_at, "
                + "succeeded_at, order_ref, provider, instrument, provider_pay_type, provider_transaction_id "
                + "FROM payment_transaction WHERE " + condition, params, (rs, i) -> detail(rs)).stream().findFirst();
        return payment.map(this::withTimeline);
    }

    private Detail withTimeline(Detail payment) {
        List<HistoryEntry> history = jdbc.query("""
                SELECT from_status, to_status, reason, occurred_at FROM payment_transaction_event
                WHERE transaction_id = :id ORDER BY occurred_at, id
                """, new MapSqlParameterSource("id", payment.id()), (rs, i) -> new HistoryEntry(
                        MoneyStatus.fromWire(rs.getString("from_status")).orElse(null),
                        status(rs.getString("to_status")),
                        rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant()));
        Composed composed = Timeline.compose(history);
        return new Detail(payment.id(), payment.kind(), payment.description(), payment.amountVnd(), payment.status(),
                payment.openedAt(), payment.succeededAt(), payment.orderRef(), payment.provider(),
                payment.instrument(), payment.providerPayType(), payment.providerTransactionId(),
                composed.outcomeCode(), composed.events());
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("description"),
                rs.getLong("amount_vnd"), rs.getString("status"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("succeeded_at")));
    }

    private static Detail detail(ResultSet rs) throws SQLException {
        Row row = row(rs);
        return new Detail(row.id(), row.kind(), row.description(), row.amountVnd(), row.status(), row.openedAt(),
                row.succeededAt(), rs.getString("order_ref"), rs.getString("provider"), rs.getString("instrument"),
                rs.getString("provider_pay_type"), rs.getString("provider_transaction_id"), null, List.of());
    }

    private static MoneyStatus status(String wire) {
        return MoneyStatus.fromWire(wire)
                .orElseThrow(() -> new IllegalStateException("A stored payment history entry has an unknown status"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
