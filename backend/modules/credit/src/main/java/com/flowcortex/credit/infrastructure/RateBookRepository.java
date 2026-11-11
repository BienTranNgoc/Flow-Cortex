package com.flowcortex.credit.infrastructure;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.domain.CapabilityPricing;
import com.flowcortex.credit.domain.RateCard;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the rate book, one statement per call and no cache, so a price committed by SQL is quoted on the next
 * call. It takes the auto-configured template, which Boot orders after Flyway, because the startup check reads
 * these tables while beans are still being built.
 */
@Repository
public class RateBookRepository {

    private static final TypeReference<Map<String, Object>> DIMENSIONS = new TypeReference<>() {
    };
    private static final String CARD_COLUMNS = "rc.id AS card_id, rc.rate_card_ref AS card_ref, "
            + "rc.pricing_unit AS card_unit, rc.provider_key AS card_provider, rc.model_key AS card_model, "
            + "rc.dimensions::text AS card_dimensions, rc.credits_per_unit AS card_price, "
            + "rc.markup_pct AS card_markup, rc.effective_from AS card_from, rc.effective_to AS card_to";

    /** The mappings of the capabilities asked for and every card row, in any window, of the refs they name. */
    public record PricingRows(Map<String, CapabilityPricing> mappings, Map<String, List<RateCard>> cardsByRef) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    RateBookRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Mapping and cards of every capability asked for, in one statement; capabilities without a mapping are absent. */
    public PricingRows findPricing(Collection<String> capabilities) {
        if (capabilities.isEmpty()) {
            return new PricingRows(Map.of(), Map.of());
        }
        Map<String, CapabilityPricing> mappings = new LinkedHashMap<>();
        Map<UUID, RateCard> cardsById = new LinkedHashMap<>();
        jdbc.query("SELECT cp.capability, cp.estimator_kind, cp.rate_card_ref, cp.pricing_unit, cp.provider_key, "
                + "cp.quantity_params, " + CARD_COLUMNS + " FROM capability_pricing cp "
                + "LEFT JOIN rate_card rc ON rc.rate_card_ref = cp.rate_card_ref "
                + "WHERE cp.capability IN (:capabilities)",
                new MapSqlParameterSource("capabilities", List.copyOf(capabilities)),
                (ResultSet rs) -> {
                    String capability = rs.getString("capability");
                    if (!mappings.containsKey(capability)) {
                        mappings.put(capability, mapping(rs, capability));
                    }
                    if (rs.getObject("card_id") != null) {
                        RateCard card = card(rs);
                        cardsById.putIfAbsent(card.id(), card);
                    }
                });
        return new PricingRows(mappings, byRef(cardsById.values()));
    }

    /** Every card row, in any window, of each ref; a ref with no rows is absent. */
    public Map<String, List<RateCard>> findCards(Collection<String> refs) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        List<RateCard> cards = jdbc.query("SELECT " + CARD_COLUMNS + " FROM rate_card rc "
                + "WHERE rc.rate_card_ref IN (:refs)",
                new MapSqlParameterSource("refs", List.copyOf(refs)), (rs, row) -> card(rs));
        return byRef(cards);
    }

    private static Map<String, List<RateCard>> byRef(Collection<RateCard> cards) {
        Map<String, List<RateCard>> byRef = new LinkedHashMap<>();
        for (RateCard card : cards) {
            byRef.computeIfAbsent(card.ref(), ref -> new ArrayList<>()).add(card);
        }
        return byRef;
    }

    private static CapabilityPricing mapping(ResultSet rs, String capability) throws SQLException {
        String wireKind = rs.getString("estimator_kind");
        CapabilityPricing.Kind kind = CapabilityPricing.Kind.fromWire(wireKind).orElseThrow(
                () -> new IllegalStateException("capability_pricing." + capability + " has unknown estimator_kind "
                        + wireKind));
        String wireUnit = rs.getString("pricing_unit");
        PricingUnit unit = wireUnit == null ? null : unit(wireUnit, "capability_pricing." + capability);
        Array params = rs.getArray("quantity_params");
        List<String> quantityParams = params == null ? List.of() : List.of((String[]) params.getArray());
        return new CapabilityPricing(capability, kind, rs.getString("rate_card_ref"), unit,
                rs.getString("provider_key"), quantityParams);
    }

    private RateCard card(ResultSet rs) throws SQLException {
        String ref = rs.getString("card_ref");
        OffsetDateTime to = rs.getObject("card_to", OffsetDateTime.class);
        return new RateCard(
                rs.getObject("card_id", UUID.class),
                ref,
                unit(rs.getString("card_unit"), "rate_card." + ref),
                rs.getString("card_provider"),
                rs.getString("card_model"),
                dimensions(rs.getString("card_dimensions")),
                rs.getBigDecimal("card_price"),
                rs.getBigDecimal("card_markup"),
                rs.getObject("card_from", OffsetDateTime.class).toInstant(),
                to == null ? null : to.toInstant());
    }

    private static PricingUnit unit(String wire, String owner) {
        return PricingUnit.fromWire(wire)
                .orElseThrow(() -> new IllegalStateException(owner + " has unknown pricing_unit " + wire));
    }

    private Map<String, Object> dimensions(String text) {
        return text == null ? null : json.readValue(text, DIMENSIONS);
    }
}
