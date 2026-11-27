package com.flowcortex.billing.history;

import com.flowcortex.billing.terms.AcceptedTermsReader;
import com.flowcortex.billing.terms.AcceptedTermsReader.AcceptedTerms;
import com.flowcortex.billing.terms.AcceptedTermsReader.Provenance;
import com.flowcortex.billing.terms.AcceptedTermsReader.PurchaseRef;
import com.flowcortex.billing.terms.TermsUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * A member's purchases with the payment terms each one is under. Every read is filtered by the caller, so
 * another member's payment is never found. Every payment is a purchase: the ledger holds only plan purchases and
 * top-ups.
 */
@Service
public class AgreementReader {

    /** What was bought; {@code acceptedAt} is when the payment was opened and {@code collectedAt} is null until paid. */
    public record Purchase(UUID transactionId, Instant acceptedAt, Instant collectedAt, long amountVnd, String currency,
            String kind, String status, String description) {
    }

    /** One purchase and where its terms come from, without the text. */
    public record AgreementRow(Purchase purchase, Provenance terms) {
    }

    /** One page of rows and the count of every payment the member has. */
    public record Page(List<AgreementRow> rows, long total) {
    }

    /** One purchase, where its terms come from, and the text. */
    public record Agreement(Purchase purchase, Provenance terms, JsonNode sections, JsonNode seoMeta) {
    }

    private record Stored(Purchase purchase, UUID pinnedRevisionId) {

        PurchaseRef ref() {
            return new PurchaseRef(purchase.transactionId(), pinnedRevisionId, purchase.acceptedAt());
        }
    }

    private static final String COLUMNS = "id, created_at, succeeded_at, amount_vnd, currency, kind, status,"
            + " description, terms_revision_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final AcceptedTermsReader terms;

    AgreementReader(NamedParameterJdbcTemplate jdbc, AcceptedTermsReader terms) {
        this.jdbc = jdbc;
        this.terms = terms;
    }

    /**
     * Newest first by opening time, then id, with the publications of the terms read once for the page.
     *
     * @throws TermsUnavailableException when a purchase of the page has no terms to show
     */
    public Page page(UUID user, HistoryQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource("user", user)
                .addValue("size", query.size())
                .addValue("offset", query.offset());
        List<Stored> stored = jdbc.query("SELECT " + COLUMNS + " FROM payment_transaction WHERE user_id = :user"
                + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset", params, (rs, i) -> stored(rs));
        Long total = jdbc.queryForObject("SELECT count(*) FROM payment_transaction WHERE user_id = :user", params,
                Long.class);
        Map<UUID, Provenance> provenance = terms.provenanceOf(stored.stream().map(Stored::ref).toList());
        List<AgreementRow> rows = stored.stream()
                .map(row -> new AgreementRow(row.purchase(), provenance.get(row.purchase().transactionId())))
                .toList();
        return new Page(rows, total == null ? 0 : total);
    }

    /**
     * The member's purchase with its terms text; empty when it is not theirs.
     *
     * @throws TermsUnavailableException when the purchase has no terms to show
     */
    public Optional<Agreement> find(UUID user, UUID transactionId) {
        Optional<Stored> stored = jdbc.query("SELECT " + COLUMNS
                + " FROM payment_transaction WHERE user_id = :user AND id = :id",
                new MapSqlParameterSource("user", user).addValue("id", transactionId), (rs, i) -> stored(rs))
                .stream().findFirst();
        return stored.map(row -> {
            AcceptedTerms accepted = terms.resolve(row.pinnedRevisionId(), row.purchase().acceptedAt());
            return new Agreement(row.purchase(), accepted.provenance(), accepted.sections(), accepted.seoMeta());
        });
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        Purchase purchase = new Purchase(rs.getObject("id", UUID.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("succeeded_at")), rs.getLong("amount_vnd"), rs.getString("currency"),
                rs.getString("kind"), rs.getString("status"), rs.getString("description"));
        return new Stored(purchase, rs.getObject("terms_revision_id", UUID.class));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
