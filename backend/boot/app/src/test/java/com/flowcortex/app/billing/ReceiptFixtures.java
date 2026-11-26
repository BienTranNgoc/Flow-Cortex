package com.flowcortex.app.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Payment rows the receipt ITs read, inserted by SQL with the split the ledger would have stored. */
final class ReceiptFixtures {

    private ReceiptFixtures() {
    }

    /** A collected payment of {@code amount} at 1,000 bps with the given split, paid at {@code paidAt}. */
    static UUID collected(JdbcTemplate jdbc, UUID member, String description, long amount, long base, long tax,
            Instant paidAt) {
        return insert(jdbc, member, description, amount, true, base, tax, paidAt);
    }

    /** A payment still pending: no paid time and no split. */
    static UUID pending(JdbcTemplate jdbc, UUID member, String description, long amount, Instant openedAt) {
        return insert(jdbc, member, description, amount, false, 0, 0, openedAt);
    }

    static String orderRef(JdbcTemplate jdbc, UUID transactionId) {
        return jdbc.queryForObject("SELECT order_ref FROM payment_transaction WHERE id = ?", String.class,
                transactionId);
    }

    private static UUID insert(JdbcTemplate jdbc, UUID member, String description, long amount, boolean paid,
            long base, long tax, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_transaction (id, user_id, kind, item_ref, description, amount_vnd, provider,
                    merchant_code, instrument, order_ref, request_ref, status, provider_transaction_id, created_at,
                    updated_at, succeeded_at, base_amount_vnd, tax_amount_vnd, tax_rate_bps, tax_rate_id,
                    tax_inclusive)
                VALUES (?, ?, 'topup', 'pkg-1k', ?, ?, 'momo', 'MOMOBKUN20180529', 'wallet', ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? THEN CAST(? AS bigint) END, CASE WHEN ? THEN CAST(? AS bigint) END,
                    CASE WHEN ? THEN 1000 END,
                    CASE WHEN ? THEN (SELECT id FROM vat_rate WHERE valid_from = DATE '1900-01-01') END,
                    CASE WHEN ? THEN TRUE END)
                """,
                id, member, description, amount, "fc_" + id.toString().replace("-", ""), UUID.randomUUID(),
                paid ? "succeeded" : "pending", paid ? "4088878653" : null, Timestamp.from(at.minusSeconds(60)),
                Timestamp.from(at), paid ? Timestamp.from(at) : null, paid, base, paid, tax, paid, paid, paid);
        return id;
    }
}
