package com.flowcortex.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The rate card tables and their seed, against the real schema. Every test runs in a rolled-back
 * transaction (a refused insert aborts it, so each refusal is its own test). The seed is asserted
 * by key and value, never by table size: the Postgres container is shared across IT classes.
 */
@Testcontainers
@SpringBootTest
@Transactional
class RateCardSchemaIT extends AbstractPostgresBootTest {

    private static final List<String> UNITS = List.of("per_request", "per_onboarding", "per_image",
            "per_1k_chars", "per_second_video", "per_minute", "per_minute_audio", "per_megapixel",
            "per_megapixel_second", "per_video", "per_1m_tokens_in", "per_1m_tokens_out");

    private static final Map<String, Integer> SEEDED_PRICES = seededPrices();

    @Autowired
    private JdbcTemplate jdbc;

    private static Map<String, Integer> seededPrices() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("echo", 1);
        prices.put("fail", 20);
        prices.put("probe", 10);
        prices.put("extract_audio", 50);
        prices.put("align_audio", 50);
        prices.put("generate_script", 100);
        prices.put("generate_speech", 100);
        prices.put("generate_storyboard", 100);
        prices.put("generate_video", 200);
        prices.put("render_video", 200);
        prices.put("translate", 100);
        prices.put("tts", 100);
        return prices;
    }

    private void insertRow(String ref, String unit, String from, String to, String dimensions, BigDecimal price) {
        jdbc.update("INSERT INTO rate_card (id, rate_card_ref, pricing_unit, provider_key, dimensions,"
                + " credits_per_unit, effective_from, effective_to)"
                + " VALUES (?, ?, ?, 'flowcortex', ?::jsonb, ?, ?::timestamptz, ?::timestamptz)",
                UUID.randomUUID(), ref, unit, dimensions, price, from, to);
    }

    private void insertRow(String ref, String unit, String from, String to) {
        insertRow(ref, unit, from, to, null, BigDecimal.ONE);
    }

    @Test
    void everyUnitIsAccepted() {
        for (String unit : UNITS) {
            insertRow("test.unit", unit, "2026-11-01T00:00:00Z", null);
        }

        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM rate_card WHERE rate_card_ref = 'test.unit'", Integer.class);
        assertThat(stored).isEqualTo(UNITS.size());
    }

    @Test
    void anUnknownUnitIsRefused() {
        assertThatThrownBy(() -> insertRow("test.unit", "per_hour", "2026-11-01T00:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> insertRow("test.window", "per_request", "2026-12-01T00:00:00Z",
                "2026-11-01T00:00:00Z")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmptyWindowIsRefused() {
        assertThatThrownBy(() -> insertRow("test.window", "per_request", "2026-12-01T00:00:00Z",
                "2026-12-01T00:00:00Z")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondOpenEndedRowForOneKeyIsRefused() {
        assertThatThrownBy(() -> insertRow("probe", "per_request", "2026-11-01T00:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondOpenEndedRowIsRefusedWhenNeitherKeyHasDimensions() {
        insertRow("test.open", "per_request", "2026-11-01T00:00:00Z", null);

        assertThatThrownBy(() -> insertRow("test.open", "per_request", "2026-12-01T00:00:00Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void openRowsOfDifferentDimensionsAreDifferentKeys() {
        insertRow("test.dim", "per_second_video", "2026-11-01T00:00:00Z", null, "{\"resolution\":\"720p\"}",
                BigDecimal.valueOf(5));
        insertRow("test.dim", "per_second_video", "2026-11-01T00:00:00Z", null, "{\"resolution\":\"1080p\"}",
                BigDecimal.valueOf(8));

        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM rate_card WHERE rate_card_ref = 'test.dim'", Integer.class);
        assertThat(stored).isEqualTo(2);
    }

    @Test
    void aSecondRowOfOneKeyWithTheSameStartIsRefused() {
        insertRow("test.start", "per_request", "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z");

        assertThatThrownBy(() -> insertRow("test.start", "per_request", "2026-11-01T00:00:00Z",
                "2026-12-15T00:00:00Z")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativePriceIsRefused() {
        assertThatThrownBy(() -> insertRow("test.price", "per_request", "2026-11-01T00:00:00Z", null, null,
                BigDecimal.valueOf(-1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNegativeMarkupIsRefused() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rate_card (id, rate_card_ref, pricing_unit, provider_key,"
                + " credits_per_unit, markup_pct, effective_from) VALUES (?, 'test.markup', 'per_request',"
                + " 'flowcortex', 1, -5, now())", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmptyObjectOfDimensionsIsRefused() {
        assertThatThrownBy(() -> insertRow("test.dim", "per_request", "2026-11-01T00:00:00Z", null, "{}",
                BigDecimal.ONE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dimensionsThatAreNotAnObjectAreRefused() {
        assertThatThrownBy(() -> insertRow("test.dim", "per_request", "2026-11-01T00:00:00Z", null, "[1]",
                BigDecimal.ONE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRateCardMappingWithoutARefIsRefused() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO capability_pricing (capability, estimator_kind,"
                + " pricing_unit, provider_key) VALUES ('test.noref', 'rate_card', 'per_request', 'flowcortex')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFreeMappingNamingARefIsRefused() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO capability_pricing (capability, estimator_kind,"
                + " rate_card_ref) VALUES ('test.free', 'free', 'probe')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownEstimatorKindIsRefused() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO capability_pricing (capability, estimator_kind)"
                + " VALUES ('test.kind', 'guess')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCapabilityHasAtMostOneMapping() {
        jdbc.update("INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('test.once', 'free')");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('test.once', 'mc')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void severalCapabilitiesMayShareOneRef() {
        insertRow("test.shared", "per_request", "2026-11-01T00:00:00Z", null);
        for (String capability : List.of("test.a", "test.b")) {
            jdbc.update("INSERT INTO capability_pricing (capability, estimator_kind, rate_card_ref, pricing_unit,"
                    + " provider_key) VALUES (?, 'rate_card', 'test.shared', 'per_request', 'flowcortex')",
                    capability);
        }

        Integer mapped = jdbc.queryForObject(
                "SELECT count(*) FROM capability_pricing WHERE rate_card_ref = 'test.shared'", Integer.class);
        assertThat(mapped).isEqualTo(2);
    }

    @Test
    void everySeededRefHasItsOpenPerRequestRowAtItsFormerPrice() {
        for (Map.Entry<String, Integer> price : SEEDED_PRICES.entrySet()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT pricing_unit, provider_key, model_key, dimensions, credits_per_unit, markup_pct,"
                            + " effective_to FROM rate_card WHERE rate_card_ref = ?", price.getKey());

            assertThat(rows).as(price.getKey()).hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("pricing_unit")).as(price.getKey()).isEqualTo("per_request");
            assertThat(row.get("provider_key")).as(price.getKey()).isEqualTo("flowcortex");
            assertThat(row.get("model_key")).as(price.getKey()).isNull();
            assertThat(row.get("dimensions")).as(price.getKey()).isNull();
            assertThat(row.get("effective_to")).as(price.getKey()).isNull();
            assertThat((BigDecimal) row.get("markup_pct")).as(price.getKey()).isEqualByComparingTo("0");
            assertThat((BigDecimal) row.get("credits_per_unit")).as(price.getKey())
                    .isEqualByComparingTo(BigDecimal.valueOf(price.getValue()));
        }
    }

    @Test
    void everySeededRateCardCapabilityMapsToItsOwnKey() {
        for (String capability : SEEDED_PRICES.keySet()) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT estimator_kind, rate_card_ref, pricing_unit, provider_key, cardinality(quantity_params)"
                            + " AS params FROM capability_pricing WHERE capability = ?", capability);

            assertThat(row.get("estimator_kind")).as(capability).isEqualTo("rate_card");
            assertThat(row.get("rate_card_ref")).as(capability).isEqualTo(capability);
            assertThat(row.get("pricing_unit")).as(capability).isEqualTo("per_request");
            assertThat(row.get("provider_key")).as(capability).isEqualTo("flowcortex");
            assertThat(((Number) row.get("params")).intValue()).as(capability).isZero();
        }
    }

    @Test
    void theApprovalCheckpointIsFree() {
        Map<String, Object> row = jdbc.queryForMap("SELECT estimator_kind, rate_card_ref, pricing_unit, provider_key"
                + " FROM capability_pricing WHERE capability = 'APPROVAL_CHECKPOINT'");

        assertThat(row.get("estimator_kind")).isEqualTo("free");
        assertThat(row.get("rate_card_ref")).isNull();
        assertThat(row.get("pricing_unit")).isNull();
        assertThat(row.get("provider_key")).isNull();
    }
}
