package com.flowcortex.billing.tax;

import com.flowcortex.shared.web.LocalizedMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets the VAT rate in force from today. The timeline is changed in one transaction that holds a lock per
 * jurisdiction and category, so two publishes run one after the other and the second sees the first's row.
 */
@Service
public class VatRatePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VatRatePublisher.class);

    private record Row(UUID id, int rateBps) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final VatRates rates;
    private final Clock clock;

    VatRatePublisher(NamedParameterJdbcTemplate jdbc, VatRates rates, Clock clock) {
        this.jdbc = jdbc;
        this.rates = rates;
        this.clock = clock;
    }

    /** Sets the rate from today's Vietnamese day. */
    @Transactional
    public PublishedVatRate publish(PublishVatRate command, UUID actor) {
        return publish(command, actor, clock.instant());
    }

    /**
     * As {@link #publish(PublishVatRate, UUID)} with the instant "now" is read at, so a caller can fix the day.
     *
     * @throws VatRateConflictException when the timeline's state refuses the change; nothing is written then
     */
    @Transactional
    public PublishedVatRate publish(PublishVatRate command, UUID actor, Instant now) {
        String jurisdiction = command.jurisdiction();
        String category = command.category();
        lock(jurisdiction, category);
        LocalDate today = VatDay.of(now);

        requireOneOpenRow(jurisdiction, category);
        Row precedent = precedentOf(jurisdiction, category, today).orElseThrow(
                () -> conflict(VatRateConflictException.NO_PRECEDENT, "vatRate.noPrecedent"));
        if (inUse(jurisdiction, category, today, precedent)) {
            throw inUse();
        }
        deleteFrom(jurisdiction, category, today);
        if (jdbc.update("UPDATE vat_rate SET valid_to = :today WHERE id = :id",
                new MapSqlParameterSource("today", today).addValue("id", precedent.id())) != 1) {
            throw new IllegalStateException("The rate row to close vanished while it was locked");
        }
        UUID created = insert(command, today);
        LOG.info("vat rate published jurisdiction={} category={} oldRateBps={} newRateBps={} effectiveFrom={} actor={}",
                jurisdiction, category, precedent.rateBps(), command.rateBps(), today, actor);
        return new PublishedVatRate(created, rates.timeline(jurisdiction, category));
    }

    private void lock(String jurisdiction, String category) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "vat_rate:" + jurisdiction + ":" + category), Object.class);
    }

    private void requireOneOpenRow(String jurisdiction, String category) {
        List<UUID> open = jdbc.queryForList("""
                SELECT id FROM vat_rate WHERE jurisdiction = :jurisdiction AND category = :category
                    AND valid_to IS NULL FOR UPDATE
                """,
                new MapSqlParameterSource("jurisdiction", jurisdiction).addValue("category", category), UUID.class);
        if (open.isEmpty()) {
            throw conflict(VatRateConflictException.NO_OPEN_ROW, "vatRate.noOpenRow");
        }
        if (open.size() > 1) {
            throw conflict(VatRateConflictException.MULTIPLE_OPEN_ROWS, "vatRate.multipleOpenRows");
        }
    }

    private Optional<Row> precedentOf(String jurisdiction, String category, LocalDate today) {
        return jdbc.query("""
                SELECT id, rate_bps FROM vat_rate WHERE jurisdiction = :jurisdiction AND category = :category
                    AND valid_from < :today ORDER BY valid_from DESC LIMIT 1
                """,
                new MapSqlParameterSource("jurisdiction", jurisdiction).addValue("category", category)
                        .addValue("today", today),
                (rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getInt("rate_bps"))).stream().findFirst();
    }

    /**
     * A payment naming a row that starts on or after today blocks its deletion; a payment collected today under
     * the row that would be closed would otherwise sit on a day the timeline says another rate covered.
     */
    private boolean inUse(String jurisdiction, String category, LocalDate today, Row precedent) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payment_transaction p
                    WHERE p.tax_rate_id IN (SELECT id FROM vat_rate WHERE jurisdiction = :jurisdiction
                            AND category = :category AND valid_from >= :today)
                        OR (p.tax_rate_id = :precedentId AND p.succeeded_at >= :todayStart))
                """,
                new MapSqlParameterSource("jurisdiction", jurisdiction).addValue("category", category)
                        .addValue("today", today).addValue("precedentId", precedent.id())
                        .addValue("todayStart", Timestamp.from(VatDay.startOf(today))),
                Boolean.class));
    }

    private void deleteFrom(String jurisdiction, String category, LocalDate today) {
        try {
            jdbc.update("DELETE FROM vat_rate WHERE jurisdiction = :jurisdiction AND category = :category "
                    + "AND valid_from >= :today",
                    new MapSqlParameterSource("jurisdiction", jurisdiction).addValue("category", category)
                            .addValue("today", today));
        } catch (DataIntegrityViolationException e) {
            throw inUse();
        }
    }

    private UUID insert(PublishVatRate command, LocalDate today) {
        String note = command.sourceNote() == null || command.sourceNote().isBlank() ? null
                : command.sourceNote().strip();
        return jdbc.queryForObject("""
                INSERT INTO vat_rate (jurisdiction, category, rate_bps, valid_from, valid_to, source_note)
                VALUES (:jurisdiction, :category, :rateBps, :today, NULL, :note) RETURNING id
                """,
                new MapSqlParameterSource("jurisdiction", command.jurisdiction())
                        .addValue("category", command.category()).addValue("rateBps", command.rateBps())
                        .addValue("today", today).addValue("note", note),
                UUID.class);
    }

    private static VatRateConflictException inUse() {
        return conflict(VatRateConflictException.IN_USE, "vatRate.inUse");
    }

    private static VatRateConflictException conflict(String code, String messageKey) {
        return new VatRateConflictException(code, new LocalizedMessage(messageKey));
    }
}
