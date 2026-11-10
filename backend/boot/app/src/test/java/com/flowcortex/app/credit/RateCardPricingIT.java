package com.flowcortex.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.credit.api.CreditAccounts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prices read from the seeded rate card through the credit port, with credits on. The retired
 * {@code step-costs.echo=99} property is set on purpose and must change nothing. Rows a test adds
 * are removed after it, and every test reads only its own keys, because the Postgres container is
 * shared across IT classes.
 */
@Testcontainers
@SpringBootTest(properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.credits.step-costs.echo=99"})
class RateCardPricingIT extends AbstractPostgresBootTest {

    private static final Map<String, Long> SEEDED_PRICES = seededPrices();

    @Autowired
    private CreditAccounts creditAccounts;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();

    private static Map<String, Long> seededPrices() {
        Map<String, Long> prices = new LinkedHashMap<>();
        prices.put("echo", 1L);
        prices.put("fail", 20L);
        prices.put("APPROVAL_CHECKPOINT", 0L);
        prices.put("probe", 10L);
        prices.put("extract_audio", 50L);
        prices.put("align_audio", 50L);
        prices.put("generate_script", 100L);
        prices.put("generate_speech", 100L);
        prices.put("generate_storyboard", 100L);
        prices.put("generate_video", 200L);
        prices.put("render_video", 200L);
        prices.put("translate", 100L);
        prices.put("tts", 100L);
        return prices;
    }

    @AfterEach
    void removeWhatTheTestAdded() {
        jdbc.update("DELETE FROM capability_pricing WHERE capability IN ('test.a', 'test.b', 'test.cap')");
        jdbc.update("DELETE FROM rate_card WHERE rate_card_ref IN ('test.shared', 'test.cap')");
        jdbc.update("DELETE FROM credit_ledger WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM credit_wallet WHERE user_id = ?", userId);
    }

    @Test
    void everyKeyPricesAsItDidBeforeTheRateCard() {
        SEEDED_PRICES.forEach((key, credits) ->
                assertThat(creditAccounts.estimate(List.of(key))).as(key).isEqualTo(credits));
    }

    @Test
    void theRetiredPropertyIsIgnored() {
        assertThat(creditAccounts.estimate(List.of("echo"))).isEqualTo(1);
    }

    @Test
    void aSeededWorkflowIsEstimatedFromTheRateCard() {
        assertThat(creditAccounts.estimate(List.of("probe", "extract_audio", "APPROVAL_CHECKPOINT", "render_video")))
                .isEqualTo(260);
    }

    @Test
    void theDubStepsAreEachPricedAtOneHundredAndAddToAWorkflowEstimate() {
        assertThat(creditAccounts.estimate(List.of("translate"))).isEqualTo(100);
        assertThat(creditAccounts.estimate(List.of("tts"))).isEqualTo(100);
        assertThat(creditAccounts.estimate(List.of("probe", "translate", "tts"))).isEqualTo(210);
    }

    @Test
    void separateAudioIsPricedPerRequest() {
        assertThat(creditAccounts.estimate(List.of("extract_audio", "separate_audio"))).isEqualTo(100);
        assertThatCode(() -> creditAccounts.requirePriced(List.of("separate_audio"))).doesNotThrowAnyException();
    }

    @Test
    void twoCapabilitiesSharingARefAreEachPricedByIt() {
        insertCard("test.shared", 3);
        mapCapability("test.a", "test.shared");
        mapCapability("test.b", "test.shared");

        assertThat(creditAccounts.estimate(List.of("test.a", "test.b"))).isEqualTo(6);
    }

    @Test
    void aCapabilityAddedByRowsOnlyIsPricedAndPassesTheStartupCheck() {
        insertCard("test.cap", 7);
        mapCapability("test.cap", "test.cap");

        assertThat(creditAccounts.estimate(List.of("test.cap", "probe"))).isEqualTo(17);
        assertThatCode(() -> creditAccounts.requirePriced(List.of("test.cap"))).doesNotThrowAnyException();
    }

    @Test
    void aTypeWithoutAMappingFailsTheCheckNamingItAndTheMappingTable() {
        assertThatThrownBy(() -> creditAccounts.requirePriced(List.of("test.cap")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.cap")
                .hasMessageContaining("capability_pricing");
    }

    @Test
    void aPricedStepIsDebitedItsRateCardPrice() {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_wallet (user_id, balance) VALUES (?, 30)", userId);

        assertThat(creditAccounts.debitForStep(userId, runId, UUID.randomUUID(), "probe")).isTrue();

        assertThat(jdbc.queryForList("SELECT amount FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, runId)).containsExactly(10L);
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class, userId))
                .isEqualTo(20);
    }

    @Test
    void aFreeStepIsAllowedAndWritesNoLedgerRow() {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_wallet (user_id, balance) VALUES (?, 30)", userId);

        assertThat(creditAccounts.debitForStep(userId, runId, UUID.randomUUID(), "APPROVAL_CHECKPOINT")).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ?", Long.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class, userId))
                .isEqualTo(30);
    }

    private void insertCard(String ref, int credits) {
        jdbc.update("INSERT INTO rate_card (id, rate_card_ref, pricing_unit, provider_key, credits_per_unit, "
                + "effective_from) VALUES (?, ?, 'per_request', 'flowcortex', ?, TIMESTAMPTZ '2026-10-18 00:00:00+00')",
                UUID.randomUUID(), ref, credits);
    }

    private void mapCapability(String capability, String ref) {
        jdbc.update("INSERT INTO capability_pricing (capability, estimator_kind, rate_card_ref, pricing_unit, "
                + "provider_key) VALUES (?, 'rate_card', ?, 'per_request', 'flowcortex')", capability, ref);
    }
}
