package com.flowcortex.billing.tax;

import com.flowcortex.billing.tax.TaxSplit.Split;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The VAT rate timeline and the rate in force on a day. A day that no row or more than one row covers is
 * refused, never answered with a default: a wrong tax on collected money cannot be corrected afterwards.
 */
@Service
public class VatRates {

    static final String JURISDICTION = "VN";
    static final String CATEGORY = "it_services";
    private static final int LOOKUP_LIMIT = 2;

    private final NamedParameterJdbcTemplate jdbc;
    private final VatTelemetry telemetry;

    VatRates(NamedParameterJdbcTemplate jdbc, VatTelemetry telemetry) {
        this.jdbc = jdbc;
        this.telemetry = telemetry;
    }

    /**
     * The one rate row covering the Vietnamese calendar day of {@code at}.
     *
     * @throws VatRateUnavailableException when no row covers the day
     * @throws VatRateAmbiguousException when more than one row does
     */
    public ResolvedRate resolve(Instant at) {
        LocalDate day = VatDay.of(at);
        List<ResolvedRate> covering;
        try {
            covering = jdbc.query("""
                    SELECT id, rate_bps FROM vat_rate
                    WHERE jurisdiction = :jurisdiction AND category = :category AND valid_from <= :day
                        AND (valid_to IS NULL OR valid_to > :day)
                    ORDER BY valid_from DESC LIMIT :limit
                    """,
                    new MapSqlParameterSource("jurisdiction", JURISDICTION)
                            .addValue("category", CATEGORY)
                            .addValue("day", day)
                            .addValue("limit", LOOKUP_LIMIT),
                    (rs, row) -> new ResolvedRate(rs.getObject("id", UUID.class), rs.getInt("rate_bps")));
        } catch (RuntimeException e) {
            telemetry.failed();
            throw e;
        }
        if (covering.isEmpty()) {
            telemetry.missing(JURISDICTION, CATEGORY, day);
            throw new VatRateUnavailableException(JURISDICTION, CATEGORY, day);
        }
        if (covering.size() > 1) {
            telemetry.ambiguous(JURISDICTION, CATEGORY, day);
            throw new VatRateAmbiguousException(JURISDICTION, CATEGORY, day);
        }
        telemetry.resolved();
        return covering.get(0);
    }

    /** The split of a tax-inclusive {@code grossVnd} at the rate in force on the day {@code collectedAt} falls on. */
    public StoredSplit splitAt(long grossVnd, Instant collectedAt) {
        ResolvedRate rate = resolve(collectedAt);
        Split split = TaxSplit.inclusive(grossVnd, rate.rateBps());
        return new StoredSplit(split.baseVnd(), split.taxVnd(), rate.rateBps(), rate.id());
    }

    /** Every row of one jurisdiction and category, oldest first. */
    public List<VatRateEntry> timeline(String jurisdiction, String category) {
        return jdbc.query("""
                SELECT id, rate_bps, valid_from, valid_to, source_note FROM vat_rate
                WHERE jurisdiction = :jurisdiction AND category = :category ORDER BY valid_from
                """,
                new MapSqlParameterSource("jurisdiction", jurisdiction).addValue("category", category),
                (rs, row) -> new VatRateEntry(rs.getObject("id", UUID.class), rs.getInt("rate_bps"),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                        rs.getString("source_note")));
    }
}
