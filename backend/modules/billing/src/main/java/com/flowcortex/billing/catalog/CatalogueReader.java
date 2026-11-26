package com.flowcortex.billing.catalog;

import com.flowcortex.billing.catalog.TierOrder.OrderableTier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the catalogue sells right now. The unretired version row is the only price of a tier, for the plans list
 * and the tier-order rule alike. A plans read is two queries, a top-up read one, none per tier.
 */
@Repository
public class CatalogueReader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NamedParameterJdbcTemplate jdbc;

    CatalogueReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A tier on sale with its unretired versions, monthly before yearly; an empty list when it has none. */
    public record PlanRow(String id, String name, String nameEn, String taglineVi, String taglineEn,
            MarketingCopy marketing, boolean popular, List<CycleRow> cycles) {
    }

    /** One cycle a tier is sold on. */
    public record CycleRow(String billingCycle, long priceVnd, long includedCreditsPerMonth) {
    }

    /** One top-up package on sale; the English copy is null when none is stored. */
    public record TopupRow(UUID id, String name, String nameEn, String description, String descriptionEn,
            String eyebrow, String eyebrowEn, long priceVnd, long credits, String groupKey, int sortOrder) {
    }

    /** Tiers on sale by position then id, each with its unretired versions. */
    public List<PlanRow> readPlans() {
        List<TierRow> tiers = jdbc.query("""
                SELECT id, name, name_en, tagline_vi, tagline_en, marketing_features::text AS marketing, popular
                FROM pricing_tier WHERE is_active ORDER BY sort_order, id
                """, (rs, i) -> tier(rs));
        Map<String, List<CycleRow>> cycles = new LinkedHashMap<>();
        jdbc.query("""
                SELECT v.tier_id, v.billing_cycle, v.price_vnd, v.included_credits_per_month
                FROM pricing_tier_version v JOIN pricing_tier t ON t.id = v.tier_id
                WHERE t.is_active AND v.retired_at IS NULL
                ORDER BY v.tier_id, CASE v.billing_cycle WHEN 'monthly' THEN 0 ELSE 1 END
                """, rs -> {
            cycles.computeIfAbsent(rs.getString("tier_id"), id -> new ArrayList<>())
                    .add(new CycleRow(rs.getString("billing_cycle"), rs.getLong("price_vnd"),
                            rs.getLong("included_credits_per_month")));
        });
        return tiers.stream()
                .map(t -> new PlanRow(t.id(), t.name(), t.nameEn(), t.taglineVi(), t.taglineEn(), t.marketing(),
                        t.popular(), List.copyOf(cycles.getOrDefault(t.id(), List.of()))))
                .toList();
    }

    /** Packages on sale by position, then price, then id. */
    public List<TopupRow> readTopupPackages() {
        return jdbc.query("""
                SELECT id, name, name_en, description, description_en, eyebrow, eyebrow_en, price_vnd, credits,
                    group_key, sort_order
                FROM topup_package WHERE active ORDER BY sort_order, price_vnd, id
                """, (rs, i) -> new TopupRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("name_en"), rs.getString("description"), rs.getString("description_en"),
                rs.getString("eyebrow"), rs.getString("eyebrow_en"), rs.getLong("price_vnd"),
                rs.getLong("credits"), rs.getString("group_key"), rs.getInt("sort_order")));
    }

    /** Tiers on sale with the price of their unretired monthly version, for the tier-order rule; one query. */
    public List<OrderableTier> readOrderableTiers() {
        return jdbc.query("""
                SELECT t.id, t.sort_order, v.price_vnd
                FROM pricing_tier t
                LEFT JOIN pricing_tier_version v
                    ON v.tier_id = t.id AND v.billing_cycle = 'monthly' AND v.retired_at IS NULL
                WHERE t.is_active ORDER BY t.sort_order, t.id
                """, (rs, i) -> {
            Long price = rs.getObject("price_vnd", Long.class);
            return new OrderableTier(rs.getString("id"), rs.getInt("sort_order"),
                    price == null ? OptionalLong.empty() : OptionalLong.of(price));
        });
    }

    private record TierRow(String id, String name, String nameEn, String taglineVi, String taglineEn,
            MarketingCopy marketing, boolean popular) {
    }

    private static TierRow tier(ResultSet rs) throws SQLException {
        return new TierRow(rs.getString("id"), rs.getString("name"), rs.getString("name_en"),
                rs.getString("tagline_vi"), rs.getString("tagline_en"),
                MarketingCopy.read(JSON.readTree(rs.getString("marketing"))), rs.getBoolean("popular"));
    }
}
