package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.GrantRule.Holding;
import com.flowcortex.billing.entitlement.HeldPlan.NextPlan;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the plan a member holds. The answer is derived from the stored holding at the instant asked, never
 * written: the raw columns outlive the period they describe, so an ended manual plan reads as Free without
 * waiting for a sweep.
 */
@Repository
public class MemberPlanReader {

    private record Names(String name, String nameEn) {
    }

    private record Stored(Holding holding, Optional<NextPlan> next, Names heldNames, Names nextNames) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    MemberPlanReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The plan the member holds at {@code now}; Free when none, with no period. */
    public MemberPlanResponse read(UUID userId, Instant now) {
        Optional<Stored> stored = find(userId);
        HeldPlan plan = HeldPlan.of(stored.map(Stored::holding), stored.flatMap(Stored::next), now);
        Names heldNames = namesOf(plan.tierId(), stored);
        MemberPlanResponse.NextPlan next = plan.next().map(upcoming -> new MemberPlanResponse.NextPlan(
                upcoming.tierId(), stored.orElseThrow().nextNames().name(), stored.orElseThrow().nextNames().nameEn(),
                upcoming.cycle(), upcoming.startsAt(), upcoming.renewsAt())).orElse(null);
        return new MemberPlanResponse(plan.tierId(), heldNames.name(), heldNames.nameEn(), plan.held(), plan.cycle(),
                plan.mode(), plan.startsAt(), plan.renewsAt(), next);
    }

    private Names namesOf(String tierId, Optional<Stored> stored) {
        if (stored.isPresent() && tierId.equals(stored.get().holding().tierId())) {
            return stored.get().heldNames();
        }
        if (stored.isPresent() && stored.get().next().map(NextPlan::tierId).filter(tierId::equals).isPresent()) {
            return stored.get().nextNames();
        }
        return tierNames(tierId);
    }

    private Names tierNames(String tierId) {
        return jdbc.query("SELECT name, name_en FROM pricing_tier WHERE id = :id",
                new MapSqlParameterSource("id", tierId),
                (rs, row) -> new Names(rs.getString("name"), rs.getString("name_en"))).stream().findFirst()
                .orElse(new Names(tierId, null));
    }

    private Optional<Stored> find(UUID userId) {
        return jdbc.query("""
                SELECT s.tier_id, s.billing_cycle, s.status, s.billing_mode, s.started_at, s.renews_at,
                    s.granted_from_transaction_id, s.pending_tier_id, s.pending_billing_cycle,
                    s.pending_transaction_id, p.period_starts_at, p.period_renews_at,
                    t.name AS tier_name, t.name_en AS tier_name_en, pt.name AS pending_name,
                    pt.name_en AS pending_name_en
                FROM member_subscription s
                LEFT JOIN pricing_tier t ON t.id = s.tier_id
                LEFT JOIN pricing_tier pt ON pt.id = s.pending_tier_id
                LEFT JOIN payment_transaction p ON p.id = s.pending_transaction_id
                WHERE s.user_id = :userId
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, row) -> {
                    UUID pending = rs.getObject("pending_transaction_id", UUID.class);
                    Holding holding = new Holding(rs.getString("tier_id"), rs.getString("billing_cycle"),
                            rs.getString("status"), rs.getString("billing_mode"),
                            rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("renews_at").toInstant(),
                            rs.getObject("granted_from_transaction_id", UUID.class), Optional.ofNullable(pending));
                    Optional<NextPlan> next = pending == null || rs.getTimestamp("period_starts_at") == null
                            ? Optional.empty()
                            : Optional.of(new NextPlan(rs.getString("pending_tier_id"),
                                    rs.getString("pending_billing_cycle"), rs.getTimestamp("period_starts_at").toInstant(),
                                    rs.getTimestamp("period_renews_at").toInstant()));
                    return new Stored(holding, next, new Names(rs.getString("tier_name"), rs.getString("tier_name_en")),
                            new Names(rs.getString("pending_name"), rs.getString("pending_name_en")));
                }).stream().findFirst();
    }
}
