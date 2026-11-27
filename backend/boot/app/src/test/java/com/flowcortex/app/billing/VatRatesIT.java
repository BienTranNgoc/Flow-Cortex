package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.tax.PublishVatRate;
import com.flowcortex.billing.tax.PublishedVatRate;
import com.flowcortex.billing.tax.ResolvedRate;
import com.flowcortex.billing.tax.StoredSplit;
import com.flowcortex.billing.tax.VatRateAmbiguousException;
import com.flowcortex.billing.tax.VatRateConflictException;
import com.flowcortex.billing.tax.VatRateEntry;
import com.flowcortex.billing.tax.VatRatePublisher;
import com.flowcortex.billing.tax.VatRateUnavailableException;
import com.flowcortex.billing.tax.VatRates;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class VatRatesIT extends AbstractPostgresBootTest {

    private static final String SEED_FROM = "1900-01-01";
    private static final Instant NOW = Instant.parse("2026-11-24T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private final UUID user = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @Autowired
    private VatRates rates;

    @Autowired
    private VatRatePublisher publisher;

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedOnly() {
        restoreSeedOnly();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
        restoreSeedOnly();
    }

    private void restoreSeedOnly() {
        jdbc.update("DELETE FROM vat_rate WHERE valid_from <> DATE '" + SEED_FROM + "'");
        jdbc.update("INSERT INTO vat_rate (jurisdiction, category, rate_bps, valid_from) "
                + "VALUES ('VN', 'it_services', 1000, DATE '" + SEED_FROM + "') ON CONFLICT DO NOTHING");
        jdbc.update("UPDATE vat_rate SET valid_to = NULL, rate_bps = 1000 WHERE valid_from = DATE '" + SEED_FROM + "'");
    }

    private UUID seedId() {
        return jdbc.queryForObject("SELECT id FROM vat_rate WHERE valid_from = DATE '" + SEED_FROM + "'", UUID.class);
    }

    private void insertRate(int bps, String from, String to) {
        jdbc.update("INSERT INTO vat_rate (jurisdiction, category, rate_bps, valid_from, valid_to) "
                + "VALUES ('VN', 'it_services', ?, CAST(? AS date), CAST(? AS date))", bps, from, to);
    }

    private void closeSeedAt(String day) {
        jdbc.update("UPDATE vat_rate SET valid_to = CAST(? AS date) WHERE valid_from = DATE '" + SEED_FROM + "'", day);
    }

    private List<VatRateEntry> timeline() {
        return rates.timeline("VN", "it_services");
    }

    private PublishedVatRate publish(int bps, String note) {
        return publisher.publish(new PublishVatRate("VN", "it_services", bps, note), actor, NOW);
    }

    private UUID openPayment() {
        OpenedPayment opened = ledger.open(new OpenPayment(user, "topup", "pkg-50k", "Top-up", 50_000L, "momo",
                "MOMOBKUN20180529", Optional.of(PaymentInstrument.WALLET)));
        return opened.transactionId();
    }

    private void collect(UUID payment, UUID rateId, Instant at) {
        jdbc.update("UPDATE payment_transaction SET status = 'succeeded', succeeded_at = ?, base_amount_vnd = 45455, "
                + "tax_amount_vnd = 4545, tax_rate_bps = 1000, tax_rate_id = ?, tax_inclusive = true WHERE id = ?",
                Timestamp.from(at), rateId, payment);
    }

    @Test
    void theSeededRateCoversAnyDay() {
        ResolvedRate resolved = rates.resolve(Instant.parse("2026-11-24T05:00:00Z"));
        assertThat(resolved.rateBps()).isEqualTo(1_000);
        assertThat(resolved.id()).isEqualTo(seedId());
        assertThat(rates.resolve(Instant.parse("1900-01-02T05:00:00Z")).rateBps()).isEqualTo(1_000);
        assertThat(timeline()).hasSize(1);
        assertThat(timeline().get(0).validFrom()).isEqualTo(LocalDate.of(1900, 1, 1));
        assertThat(timeline().get(0).validTo()).isNull();
    }

    @Test
    void theRateChangesAtVietnamMidnight() {
        closeSeedAt("2026-07-01");
        insertRate(800, "2026-07-01", null);

        assertThat(rates.resolve(Instant.parse("2026-06-30T10:00:00Z")).rateBps()).isEqualTo(1_000);
        assertThat(rates.resolve(Instant.parse("2026-06-30T16:59:59Z")).rateBps()).isEqualTo(1_000);
        assertThat(rates.resolve(Instant.parse("2026-06-30T17:00:00Z")).rateBps()).isEqualTo(800);
        assertThat(rates.resolve(Instant.parse("2026-07-01T10:00:00Z")).rateBps()).isEqualTo(800);
    }

    @Test
    void splitAtAppliesTheRateOfTheCollectionDay() {
        closeSeedAt("2026-07-01");
        insertRate(800, "2026-07-01", null);

        StoredSplit before = rates.splitAt(50_000, Instant.parse("2026-06-30T10:00:00Z"));
        StoredSplit after = rates.splitAt(50_000, Instant.parse("2026-07-01T10:00:00Z"));

        assertThat(before.baseVnd()).isEqualTo(45_455);
        assertThat(before.taxVnd()).isEqualTo(4_545);
        assertThat(before.rateBps()).isEqualTo(1_000);
        assertThat(before.rateId()).isEqualTo(seedId());
        assertThat(after.baseVnd()).isEqualTo(46_296);
        assertThat(after.taxVnd()).isEqualTo(3_704);
        assertThat(after.rateBps()).isEqualTo(800);
    }

    @Test
    void aDayNoRowCoversIsRefused() {
        closeSeedAt("2026-07-01");

        assertThatThrownBy(() -> rates.resolve(Instant.parse("2026-07-01T10:00:00Z")))
                .isInstanceOf(VatRateUnavailableException.class)
                .satisfies(e -> {
                    VatRateUnavailableException gap = (VatRateUnavailableException) e;
                    assertThat(gap.jurisdiction()).isEqualTo("VN");
                    assertThat(gap.category()).isEqualTo("it_services");
                    assertThat(gap.day()).isEqualTo(LocalDate.of(2026, 7, 1));
                });
    }

    @Test
    void aDayTwoRowsCoverIsRefused() {
        insertRate(900, "2026-07-01", null);

        assertThatThrownBy(() -> rates.resolve(Instant.parse("2026-07-02T10:00:00Z")))
                .isInstanceOf(VatRateAmbiguousException.class);
    }

    @Test
    void theDatabaseRefusesInvalidRateRows() {
        assertThatThrownBy(() -> insertRate(10_001, "2026-07-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRate(800, "2026-07-01", "2026-07-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO vat_rate (jurisdiction, category, rate_bps, valid_from) "
                + "VALUES ('VN', 'food', 800, DATE '2026-07-01')")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRate(800, SEED_FROM, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSplitThatDoesNotSumIsRefused() {
        UUID payment = openPayment();

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET base_amount_vnd = 45000, "
                + "tax_amount_vnd = 4545, tax_rate_bps = 1000, tax_rate_id = ?, tax_inclusive = true WHERE id = ?",
                seedId(), payment)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSplitWithMissingColumnsIsRefused() {
        UUID payment = openPayment();

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET base_amount_vnd = 45455, "
                + "tax_amount_vnd = 4545 WHERE id = ?", payment)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSplitNamingNoRateRowIsRefused() {
        UUID payment = openPayment();

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET base_amount_vnd = 45455, "
                + "tax_amount_vnd = 4545, tax_rate_bps = 1000, tax_rate_id = ?, tax_inclusive = true WHERE id = ?",
                UUID.randomUUID(), payment)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPaidTimeWithoutASplitIsRefused() {
        UUID payment = openPayment();

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET status = 'succeeded', "
                + "succeeded_at = now() WHERE id = ?", payment)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aWrittenSplitCannotBeChanged() {
        UUID payment = openPayment();
        collect(payment, seedId(), NOW);

        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET tax_rate_bps = 800 WHERE id = ?", payment))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET base_amount_vnd = NULL, "
                + "tax_amount_vnd = NULL, tax_rate_bps = NULL, tax_rate_id = NULL, tax_inclusive = NULL "
                + "WHERE id = ?", payment)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRateRowAPaymentNamesCannotBeDeleted() {
        UUID payment = openPayment();
        collect(payment, seedId(), NOW);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM vat_rate WHERE id = ?", seedId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settingTheRateClosesTheOpenRowAtTodayAndOpensTheNewOne() {
        PublishedVatRate published = publish(800, "Nghị quyết 204/2025/QH15");

        List<VatRateEntry> rows = timeline();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).rateBps()).isEqualTo(1_000);
        assertThat(rows.get(0).validFrom()).isEqualTo(LocalDate.of(1900, 1, 1));
        assertThat(rows.get(0).validTo()).isEqualTo(TODAY);
        assertThat(rows.get(1).rateBps()).isEqualTo(800);
        assertThat(rows.get(1).validFrom()).isEqualTo(TODAY);
        assertThat(rows.get(1).validTo()).isNull();
        assertThat(rows.get(1).sourceNote()).isEqualTo("Nghị quyết 204/2025/QH15");
        assertThat(published.publishedRateId()).isEqualTo(rows.get(1).id());
        assertThat(published.rates()).isEqualTo(rows);
    }

    @Test
    void settingTheRateTwiceOnOneDayKeepsOneRowFromToday() {
        publish(800, null);
        publish(900, null);

        List<VatRateEntry> rows = timeline();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).validTo()).isEqualTo(TODAY);
        assertThat(rows.get(1).rateBps()).isEqualTo(900);
        assertThat(rows.get(1).validFrom()).isEqualTo(TODAY);
    }

    @Test
    void aRowScheduledAfterTodayIsReplaced() {
        closeSeedAt("2026-12-01");
        insertRate(800, "2026-12-01", null);

        publish(900, null);

        List<VatRateEntry> rows = timeline();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).validTo()).isEqualTo(TODAY);
        assertThat(rows.get(1).rateBps()).isEqualTo(900);
    }

    @Test
    void aBlankNoteIsStoredAsNullAndANoteIsTrimmed() {
        publish(800, "   ");
        assertThat(timeline().get(1).sourceNote()).isNull();

        publish(800, "  decree  ");
        assertThat(timeline().get(1).sourceNote()).isEqualTo("decree");
    }

    @Test
    void aTimelineWithNoOpenRowIsRefusedAndLeftAsItWas() {
        closeSeedAt("2026-07-01");
        List<VatRateEntry> before = timeline();

        assertConflict(() -> publish(800, null), "vat_rate_no_open_row");
        assertThat(timeline()).isEqualTo(before);
    }

    @Test
    void aTimelineWithTwoOpenRowsIsRefusedAndLeftAsItWas() {
        insertRate(900, "2026-07-01", null);
        List<VatRateEntry> before = timeline();

        assertConflict(() -> publish(800, null), "vat_rate_multiple_open_rows");
        assertThat(timeline()).isEqualTo(before);
    }

    @Test
    void aTimelineWithNoRowBeforeTodayIsRefusedAndLeftAsItWas() {
        jdbc.update("DELETE FROM vat_rate");
        insertRate(900, TODAY.toString(), null);
        List<VatRateEntry> before = timeline();

        assertConflict(() -> publish(800, null), "vat_rate_no_precedent");
        assertThat(timeline()).isEqualTo(before);
    }

    @Test
    void aRowStartingTodayThatAPaymentNamesIsKept() {
        UUID today = publish(800, null).publishedRateId();
        collect(openPayment(), today, NOW);
        List<VatRateEntry> before = timeline();

        assertConflict(() -> publish(900, null), "vat_rate_in_use");
        assertThat(timeline()).isEqualTo(before);
    }

    @Test
    void theOpenRowAPaymentOfTodayNamesCannotBeClosed() {
        collect(openPayment(), seedId(), Instant.parse("2026-11-23T17:30:00Z"));
        List<VatRateEntry> before = timeline();

        assertConflict(() -> publish(800, null), "vat_rate_in_use");
        assertThat(timeline()).isEqualTo(before);
    }

    @Test
    void aPaymentOfYesterdayDoesNotBlockSettingTheRate() {
        collect(openPayment(), seedId(), Instant.parse("2026-11-23T16:30:00Z"));

        publish(800, null);

        assertThat(timeline()).hasSize(2);
    }

    @Test
    void twoSetsAtOnceRunOneAfterTheOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<PublishedVatRate>> calls = new ArrayList<>();
            for (int bps : new int[] {800, 900}) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return publish(bps, null);
                }));
            }
            go.countDown();
            for (Future<PublishedVatRate> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<VatRateEntry> rows = timeline();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row -> row.validTo() == null)).hasSize(1);
        assertThat(rows.get(1).validFrom()).isEqualTo(TODAY);
    }

    private static void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(VatRateConflictException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
