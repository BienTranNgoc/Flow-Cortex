package com.flowcortex.billing.receipt;

import com.flowcortex.billing.receipt.Receipt.Line;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the receipt of a member's own payment; one query filtered by the owner, so no one else's is ever found. */
@Repository
@EnableConfigurationProperties(ReceiptProperties.class)
public class ReceiptReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReceiptProperties properties;

    ReceiptReader(NamedParameterJdbcTemplate jdbc, ReceiptProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * The receipt of {@code member}'s payment, empty when it is not theirs or does not exist.
     *
     * @throws ReceiptNotCollectedException when the payment is theirs but has no paid time yet
     */
    public Optional<Receipt> findOwn(UUID member, UUID transactionId) {
        return jdbc.query("""
                SELECT id, order_ref, kind, description, amount_vnd, currency, provider, instrument,
                    provider_transaction_id, succeeded_at, base_amount_vnd, tax_amount_vnd, tax_rate_bps
                FROM payment_transaction WHERE id = :id AND user_id = :member
                """,
                new MapSqlParameterSource("id", transactionId).addValue("member", member),
                (rs, row) -> receipt(rs)).stream().findFirst();
    }

    private Receipt receipt(ResultSet rs) throws SQLException {
        if (rs.getTimestamp("succeeded_at") == null) {
            throw new ReceiptNotCollectedException();
        }
        long base = rs.getLong("base_amount_vnd");
        long tax = rs.getLong("tax_amount_vnd");
        int rateBps = rs.getInt("tax_rate_bps");
        if (rs.wasNull()) {
            throw new IllegalStateException("A collected payment has no stored tax split");
        }
        long total = rs.getLong("amount_vnd");
        return new Receipt(rs.getObject("id", UUID.class), rs.getString("order_ref"), rs.getString("kind"),
                rs.getTimestamp("succeeded_at").toInstant(), rs.getString("provider"), rs.getString("instrument"),
                rs.getString("provider_transaction_id"), properties.sellerBlock(),
                List.of(new Line(rs.getString("description"), 1, base, base)), base, rateBps, tax, total,
                rs.getString("currency"), AmountInWords.vietnamese(total));
    }
}
