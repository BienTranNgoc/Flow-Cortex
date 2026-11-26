package com.flowcortex.billing.receipt;

import com.flowcortex.settings.api.Settings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Queues the receipt mail of a payment at the moment its money is collected. */
@Component
public class ReceiptDeliveries {

    private final NamedParameterJdbcTemplate jdbc;
    private final Settings settings;

    ReceiptDeliveries(NamedParameterJdbcTemplate jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * Writes the payment's delivery row, due at {@code now}, when the receipt mail is on at this moment and the
     * payment has none yet. Call it inside the transaction that moves the payment to succeeded, so a settlement
     * that rolls back leaves no row; the switch is read here only, so a later change never cancels the row.
     */
    public void openOnCollection(UUID transactionId, Instant now) {
        if (!Boolean.TRUE.equals(settings.get(ReceiptSettings.EMAIL_ENABLED).effective())) {
            return;
        }
        jdbc.update("""
                INSERT INTO payment_receipt_delivery (transaction_id, user_id, created_at, next_attempt_at)
                SELECT id, user_id, :now, :now FROM payment_transaction WHERE id = :id
                ON CONFLICT (transaction_id) DO NOTHING
                """,
                new MapSqlParameterSource("id", transactionId).addValue("now", Timestamp.from(now)));
    }
}
