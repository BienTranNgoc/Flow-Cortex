package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.catalog.CatalogueReader;
import com.flowcortex.billing.catalog.CatalogueReader.CycleRow;
import com.flowcortex.billing.catalog.CatalogueReader.PlanRow;
import com.flowcortex.billing.catalog.CatalogueReader.TopupRow;
import com.flowcortex.billing.catalog.TierOrder;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Postgres container is shared by the whole :boot:app run, so seeded rows are never changed outside a
 * rolled-back transaction: every case that writes does so inside {@link #rolledBack}.
 */
@SpringBootTest
class PlanCatalogueIT extends AbstractPostgresBootTest {

    @Autowired
    private CatalogueReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private void rolledBack(Runnable work) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }

    private PlanRow plan(String id) {
        return reader.readPlans().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private void insertTier(String id, int sortOrder, boolean active) {
        jdbc.update("INSERT INTO pricing_tier (id, name, sort_order, is_active) VALUES (?, ?, ?, ?)", id, id,
                sortOrder, active);
    }

    @Test
    void theSeededCatalogueHasFourTiersFourMonthlyVersionsAndEightPackages() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_tier", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pricing_tier_version WHERE billing_cycle = 'monthly' AND retired_at IS NULL",
                Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_tier_version", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM topup_package", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing_tier WHERE popular", Long.class)).isZero();
        assertThat(TierOrder.violations(reader.readOrderableTiers())).isEmpty();
    }

    @Test
    void theSeededTiersCarryXoodPricesCreditsAndCopy() {
        assertThat(reader.readPlans())
                .extracting(PlanRow::id, p -> p.cycles().stream()
                        .map(c -> tuple(c.billingCycle(), c.priceVnd(), c.includedCreditsPerMonth())).toList())
                .containsExactly(
                        tuple("free", List.of(tuple("monthly", 0L, 0L))),
                        tuple("basic", List.of(tuple("monthly", 849_000L, 45_000L))),
                        tuple("advanced", List.of(tuple("monthly", 2_499_000L, 120_000L))),
                        tuple("professional", List.of(tuple("monthly", 5_799_000L, 300_000L))));
        PlanRow basic = plan("basic");
        assertThat(basic.name()).isEqualTo("Cơ bản");
        assertThat(basic.nameEn()).isEqualTo("Starter");
        assertThat(basic.popular()).isFalse();
        assertThat(basic.marketing().vi()).containsExactly("45.000 Credit/tháng", "Toàn bộ workflow",
                "Ưu tiên chạy tiêu chuẩn");
        assertThat(basic.marketing().en()).containsExactly("45,000 credits per month", "Every workflow",
                "Standard run priority");
        assertThat(plan("free").marketing().vi()).isEmpty();
    }

    @Test
    void theSeededPackagesAreInOrderWithNoEnglishCopy() {
        List<TopupRow> packages = reader.readTopupPackages();

        assertThat(packages).extracting(TopupRow::name).containsExactly("Trải Nghiệm", "Tiết kiệm",
                "Linh hoạt 50.000", "Linh hoạt 60.000", "Linh hoạt 70.000", "Linh hoạt 80.000", "Linh hoạt 90.000",
                "Linh hoạt 100.000");
        assertThat(packages).extracting(TopupRow::priceVnd, TopupRow::credits, TopupRow::groupKey)
                .startsWith(tuple(50_000L, 10_000L, null), tuple(150_000L, 35_000L, null),
                        tuple(300_000L, 50_000L, "flexible"))
                .endsWith(tuple(550_000L, 100_000L, "flexible"));
        assertThat(packages).allSatisfy(p -> {
            assertThat(p.nameEn()).isNull();
            assertThat(p.descriptionEn()).isNull();
            assertThat(p.eyebrowEn()).isNull();
            assertThat(p.description()).isNotBlank();
        });
    }

    static Stream<Consumer<JdbcTemplate>> refusedWrites() {
        return Stream.of(
                db -> db.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                        + "included_credits_per_month, effective_from) VALUES ('basic', 'weekly', 1, 1, now())"),
                db -> db.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                        + "included_credits_per_month, effective_from) VALUES ('basic', 'yearly', -1, 1, now())"),
                db -> db.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                        + "included_credits_per_month, effective_from) VALUES ('basic', 'yearly', 1, -1, now())"),
                db -> db.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                        + "included_credits_per_month, effective_from, retired_at) VALUES ('basic', 'yearly', 1, 1, "
                        + "'2026-12-01T00:00:00Z', '2026-11-30T00:00:00Z')"),
                db -> db.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                        + "included_credits_per_month, effective_from) VALUES ('it_missing', 'monthly', 1, 1, now())"),
                db -> db.update("INSERT INTO topup_package (name, price_vnd, credits) VALUES ('Free', 0, 1)"),
                db -> db.update("INSERT INTO topup_package (name, price_vnd, credits) VALUES ('None', 1, 0)"),
                db -> db.update("INSERT INTO pricing_tier (id, name) VALUES ('Bad-Id', 'Bad')"),
                db -> db.update("INSERT INTO pricing_tier (id, name) VALUES ('blank_name', '   ')"),
                db -> {
                    db.update("UPDATE pricing_tier SET popular = true WHERE id = 'advanced'");
                    db.update("UPDATE pricing_tier SET popular = true WHERE id = 'professional'");
                },
                db -> db.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                        + "included_credits_per_month, effective_from) VALUES ('basic', 'monthly', 1, 1, now())"));
    }

    @ParameterizedTest
    @MethodSource("refusedWrites")
    void theDatabaseRefusesAnInvalidRow(Consumer<JdbcTemplate> write) {
        assertThatThrownBy(() -> rolledBack(() -> write.accept(jdbc)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPriceChangeByRetireAndInsertIsSeenByTheListAndTheOrderRule() {
        rolledBack(() -> {
            jdbc.update("UPDATE pricing_tier_version SET retired_at = now() "
                    + "WHERE tier_id = 'basic' AND retired_at IS NULL");
            jdbc.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                    + "included_credits_per_month, effective_from) VALUES ('basic', 'monthly', 899000, 45000, now())");

            assertThat(plan("basic").cycles()).containsExactly(new CycleRow("monthly", 899_000, 45_000));
            assertThat(reader.readOrderableTiers()).filteredOn(t -> t.id().equals("basic"))
                    .singleElement().extracting(t -> t.monthlyPriceVnd().getAsLong()).isEqualTo(899_000L);
        });
    }

    @Test
    void theListHoldsOnlyTiersOnSaleAndUnretiredVersionsMonthlyBeforeYearly() {
        rolledBack(() -> {
            jdbc.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                    + "included_credits_per_month, effective_from) VALUES ('basic', 'yearly', 10188000, 45000, now())");
            jdbc.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                    + "included_credits_per_month, effective_from, retired_at) "
                    + "VALUES ('basic', 'monthly', 799000, 45000, now() - interval '2 days', now() - interval '1 day')");
            insertTier("legacy", 1, false);
            jdbc.update("INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, "
                    + "included_credits_per_month, effective_from) VALUES ('legacy', 'monthly', 1, 1, now())");
            insertTier("team", 4, true);

            List<PlanRow> plans = reader.readPlans();

            assertThat(plans).extracting(PlanRow::id).containsExactly("free", "basic", "advanced", "professional",
                    "team");
            assertThat(plan("basic").cycles()).containsExactly(new CycleRow("monthly", 849_000, 45_000),
                    new CycleRow("yearly", 10_188_000, 45_000));
            assertThat(plan("team").cycles()).isEmpty();
            assertThat(reader.readOrderableTiers()).filteredOn(t -> t.id().equals("team"))
                    .singleElement().extracting(t -> t.monthlyPriceVnd().isEmpty()).isEqualTo(true);
        });
    }

    @Test
    void tiersAtTheSamePositionAreOrderedById() {
        rolledBack(() -> {
            insertTier("tie_b", 9, true);
            insertTier("tie_a", 9, true);

            assertThat(reader.readPlans()).extracting(PlanRow::id).containsExactly("free", "basic", "advanced",
                    "professional", "tie_a", "tie_b");
        });
    }

    @Test
    void packagesAtTheSamePositionAndPriceAreOrderedById() {
        rolledBack(() -> {
            jdbc.update("INSERT INTO topup_package (id, name, price_vnd, credits, sort_order) VALUES "
                    + "('00000000-0000-0000-0000-00000000000b', 'Tie B', 40000, 5000, 1)");
            jdbc.update("INSERT INTO topup_package (id, name, price_vnd, credits, sort_order) VALUES "
                    + "('00000000-0000-0000-0000-00000000000a', 'Tie A', 40000, 5000, 1)");

            assertThat(reader.readTopupPackages()).extracting(TopupRow::name).hasSize(10)
                    .startsWith("Tie A", "Tie B", "Trải Nghiệm");
        });
    }

    @Test
    void aMalformedMarketingListReadsAsTheTextEntriesItHolds() {
        rolledBack(() -> {
            jdbc.update("UPDATE pricing_tier SET marketing_features = '{\"vi\": [\"45.000 Credit/tháng\", 7]}'::jsonb "
                    + "WHERE id = 'basic'");

            assertThat(plan("basic").marketing().vi()).containsExactly("45.000 Credit/tháng");
            assertThat(plan("basic").marketing().en()).isEmpty();
        });
    }

    @Test
    void anOffSalePackageIsAbsentAndTiesBreakByPriceThenId() {
        rolledBack(() -> {
            jdbc.update("UPDATE topup_package SET active = false WHERE name = 'Tiết kiệm'");
            jdbc.update("INSERT INTO topup_package (name, price_vnd, credits, sort_order) VALUES ('Cheaper', 40000, 5000, 1)");

            assertThat(reader.readTopupPackages()).extracting(TopupRow::name).hasSize(8)
                    .startsWith("Cheaper", "Trải Nghiệm").doesNotContain("Tiết kiệm");
        });
    }
}
