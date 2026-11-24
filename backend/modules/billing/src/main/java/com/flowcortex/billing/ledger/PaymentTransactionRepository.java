package com.flowcortex.billing.ledger;

import com.flowcortex.billing.ledger.LedgerDecision.ProviderFacts;
import com.flowcortex.billing.ledger.LedgerDecision.TransactionSnapshot;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentOrderLookup.RecordedOrder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence of payments and their status history. The history is append-only: no method updates or deletes it. */
@Repository
class PaymentTransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    PaymentTransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertOpened(UUID id, OpenPayment opening, String orderRef, String requestRef, Instant now) {
        jdbc.update("""
                INSERT INTO payment_transaction (id, user_id, kind, item_ref, description, amount_vnd, provider,
                    merchant_code, instrument, order_ref, request_ref, status, created_at, updated_at)
                VALUES (:id, :userId, :kind, :itemRef, :description, :amountVnd, :provider, :merchantCode,
                    :instrument, :orderRef, CAST(:requestRef AS uuid), 'pending', :now, :now)
                """,
                new MapSqlParameterSource("id", id)
                        .addValue("userId", opening.userId())
                        .addValue("kind", opening.kind())
                        .addValue("itemRef", opening.itemRef())
                        .addValue("description", opening.description())
                        .addValue("amountVnd", opening.amountVnd())
                        .addValue("provider", opening.provider())
                        .addValue("merchantCode", opening.merchantCode())
                        .addValue("instrument", opening.instrument().map(PaymentInstrument::wire).orElse(null))
                        .addValue("orderRef", orderRef)
                        .addValue("requestRef", requestRef)
                        .addValue("now", Timestamp.from(now)));
    }

    /**
     * Appends one history entry. {@code from} is null only for the opening; the evidence is the id of what
     * caused the move, null for the opening.
     */
    void insertHistory(UUID transactionId, MoneyStatus from, MoneyStatus to, String reason, String resultCode,
            String evidenceRef, Instant providerTimestamp, Instant now) {
        jdbc.update("""
                INSERT INTO payment_transaction_event (id, transaction_id, from_status, to_status, reason,
                    provider_result_code, evidence_ref, provider_timestamp, occurred_at)
                VALUES (:id, :transactionId, :from, :to, :reason, :resultCode, :evidenceRef, :providerTimestamp, :now)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("transactionId", transactionId)
                        .addValue("from", from == null ? null : from.wire())
                        .addValue("to", to.wire())
                        .addValue("reason", reason)
                        .addValue("resultCode", resultCode)
                        .addValue("evidenceRef", evidenceRef)
                        .addValue("providerTimestamp", timestamp(providerTimestamp))
                        .addValue("now", Timestamp.from(now)));
    }

    /**
     * The provider's payment for an order reference, locked until the surrounding transaction ends so two
     * callbacks for one payment are decided one after the other. Call it inside a transaction.
     */
    Optional<TransactionSnapshot> lockByOrderRef(String provider, String orderRef) {
        return jdbc.query("""
                SELECT id, status, amount_vnd, provider_status_at FROM payment_transaction
                WHERE provider = :provider AND order_ref = :orderRef FOR UPDATE
                """,
                new MapSqlParameterSource("provider", provider).addValue("orderRef", orderRef),
                (rs, row) -> new TransactionSnapshot(
                        rs.getObject("id", UUID.class),
                        MoneyStatus.fromWire(rs.getString("status")).orElseThrow(
                                () -> new IllegalStateException("A stored payment has an unknown status")),
                        rs.getLong("amount_vnd"),
                        instant(rs.getTimestamp("provider_status_at")))).stream().findFirst();
    }

    /**
     * Moves the payment when it is still at {@code from}; the row count says whether it was. The paid time is
     * set only by a move to {@code succeeded} and is kept by every later move.
     */
    int applyMove(UUID id, MoneyStatus from, MoneyStatus to, ProviderFacts facts, Instant now) {
        return jdbc.update("""
                UPDATE payment_transaction SET status = :to, provider_result_code = :resultCode,
                    provider_message = :message, provider_transaction_id = :providerTransactionId,
                    provider_pay_type = :payType, provider_status_at = :providerTimestamp, updated_at = :now,
                    succeeded_at = COALESCE(CAST(:succeededAt AS timestamptz), succeeded_at)
                WHERE id = :id AND status = :from
                """,
                new MapSqlParameterSource("id", id)
                        .addValue("from", from.wire())
                        .addValue("to", to.wire())
                        .addValue("resultCode", facts.resultCode())
                        .addValue("message", facts.message())
                        .addValue("providerTransactionId", facts.providerTransactionId())
                        .addValue("payType", facts.payType())
                        .addValue("providerTimestamp", timestamp(facts.providerTimestamp()))
                        .addValue("succeededAt", to == MoneyStatus.SUCCEEDED ? Timestamp.from(now) : null)
                        .addValue("now", Timestamp.from(now)));
    }

    Optional<RecordedOrder> findRecordedOrder(String provider, String orderRef) {
        return jdbc.query("""
                SELECT amount_vnd, merchant_code FROM payment_transaction
                WHERE provider = :provider AND order_ref = :orderRef
                """,
                new MapSqlParameterSource("provider", provider).addValue("orderRef", orderRef),
                (rs, row) -> new RecordedOrder(rs.getLong("amount_vnd"), rs.getString("merchant_code")))
                .stream().findFirst();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
