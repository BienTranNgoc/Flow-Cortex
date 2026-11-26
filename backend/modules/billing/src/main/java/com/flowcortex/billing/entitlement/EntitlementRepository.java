package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.GrantRule.Holding;
import com.flowcortex.billing.entitlement.PlanPeriods.Period;
import com.flowcortex.billing.ledger.PlanItemRef;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the plan holding and its history. Every method runs inside the caller's transaction. The
 * writes carry their guard in the statement and return the row count, so a caller that decided on a read made
 * under the member lock can tell when the row is no longer what it read. The history is append-only.
 */
@Repository
class EntitlementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    EntitlementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A payment as the grant reads it. */
    record PlanPurchase(UUID userId, String kind, String itemRef) {
    }

    /** The holding with the paid time of the payment it was granted from. */
    record HeldRow(Holding holding, Instant grantedSucceededAt) {
    }

    /** A recorded next-cycle plan whose period has begun, with what the holding reads before it is replaced. */
    record DuePending(UUID transactionId, String tierId, String cycle, Instant startsAt, Instant renewsAt,
            String fromTierId, String fromStatus) {
    }

    /**
     * Waits for and takes the member's plan lock until the transaction ends. A row lock cannot do it: a member's
     * first purchase has no holding row to lock.
     */
    void lockMember(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('member-plan:' || :userId, 0))",
                new MapSqlParameterSource("userId", userId.toString()), rs -> {
                });
    }

    Optional<PlanPurchase> findPlanPurchase(UUID transactionId) {
        return jdbc.query("SELECT user_id, kind, item_ref FROM payment_transaction WHERE id = :id",
                new MapSqlParameterSource("id", transactionId),
                (rs, row) -> new PlanPurchase(rs.getObject("user_id", UUID.class), rs.getString("kind"),
                        rs.getString("item_ref"))).stream().findFirst();
    }

    /** Whether the catalogue has ever priced this tier for this cycle; the free tier is never sold. */
    boolean planSold(String tierId, String cycle) {
        return !HeldPlan.FREE.equals(tierId) && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pricing_tier_version WHERE tier_id = :tier AND billing_cycle = :cycle)
                """,
                new MapSqlParameterSource("tier", tierId).addValue("cycle", cycle), Boolean.class));
    }

    /** The priced, unretired cycles of active tiers: the ones a member can buy now. */
    List<PlanPeriodCheck.SoldCycle> soldCycles() {
        return jdbc.getJdbcOperations().query("""
                SELECT v.tier_id, v.billing_cycle FROM pricing_tier_version v
                JOIN pricing_tier t ON t.id = v.tier_id
                WHERE v.retired_at IS NULL AND v.price_vnd > 0 AND t.is_active
                ORDER BY v.tier_id, v.billing_cycle
                """,
                (rs, row) -> new PlanPeriodCheck.SoldCycle(rs.getString("tier_id"), rs.getString("billing_cycle")));
    }

    Optional<HeldRow> findHolding(UUID userId) {
        return jdbc.query("""
                SELECT s.tier_id, s.billing_cycle, s.status, s.billing_mode, s.started_at, s.renews_at,
                    s.granted_from_transaction_id, s.pending_transaction_id, p.succeeded_at
                FROM member_subscription s
                JOIN payment_transaction p ON p.id = s.granted_from_transaction_id
                WHERE s.user_id = :userId
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, row) -> new HeldRow(new Holding(rs.getString("tier_id"), rs.getString("billing_cycle"),
                        rs.getString("status"), rs.getString("billing_mode"), instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("renews_at")), rs.getObject("granted_from_transaction_id", UUID.class),
                        Optional.ofNullable(rs.getObject("pending_transaction_id", UUID.class))),
                        instant(rs.getTimestamp("succeeded_at")))).stream().findFirst();
    }

    /**
     * The furthest renewal of the member's succeeded plan purchases whose period the holding does not yet
     * reflect: those that succeeded after the payment it was granted from, all of them when {@code afterSucceededAt}
     * is null. Null when there is none.
     */
    Instant furthestUnreflected(UUID userId, Instant afterSucceededAt) {
        return jdbc.query("""
                SELECT max(period_renews_at) AS furthest FROM payment_transaction
                WHERE user_id = :userId AND kind = 'plan_purchase' AND status = 'succeeded'
                  AND period_renews_at IS NOT NULL
                  AND (CAST(:after AS timestamptz) IS NULL OR succeeded_at > CAST(:after AS timestamptz))
                """,
                new MapSqlParameterSource("userId", userId).addValue("after", timestamp(afterSucceededAt)),
                rs -> rs.next() ? instant(rs.getTimestamp("furthest")) : null);
    }

    /** Stores the period when the payment holds none; the row count says whether it did. */
    int pinPeriod(UUID transactionId, Period period) {
        return jdbc.update("""
                UPDATE payment_transaction SET period_starts_at = :startsAt, period_renews_at = :renewsAt
                WHERE id = :id AND period_starts_at IS NULL
                """,
                new MapSqlParameterSource("id", transactionId)
                        .addValue("startsAt", Timestamp.from(period.startsAt()))
                        .addValue("renewsAt", Timestamp.from(period.renewsAt())));
    }

    /** Opens the member's holding; a primary-key violation means the member lock was not held. */
    void open(UUID userId, PlanItemRef plan, Period period, UUID transactionId, Instant now) {
        jdbc.update("""
                INSERT INTO member_subscription (user_id, tier_id, billing_cycle, status, billing_mode, started_at,
                    renews_at, granted_from_transaction_id, created_at, updated_at)
                VALUES (:userId, :tier, :cycle, 'active', 'manual', :startsAt, :renewsAt, :tx, :now, :now)
                """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("tier", plan.tierId())
                        .addValue("cycle", plan.cycle())
                        .addValue("startsAt", Timestamp.from(period.startsAt()))
                        .addValue("renewsAt", Timestamp.from(period.renewsAt()))
                        .addValue("tx", transactionId)
                        .addValue("now", Timestamp.from(now)));
    }

    /**
     * Moves the holding onto the purchased plan unless it is granted from this payment or from one that succeeded
     * at or after {@code settledAt}.
     */
    int move(UUID userId, PlanItemRef plan, Period period, UUID transactionId, Instant settledAt, Instant now) {
        return jdbc.update("""
                UPDATE member_subscription SET tier_id = :tier, billing_cycle = :cycle, status = 'active',
                    started_at = :startsAt, renews_at = :renewsAt, granted_from_transaction_id = :tx, updated_at = :now
                WHERE user_id = :userId AND granted_from_transaction_id <> :tx
                  AND NOT EXISTS (SELECT 1 FROM payment_transaction a
                      WHERE a.id = member_subscription.granted_from_transaction_id AND a.succeeded_at >= :settledAt)
                """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("tier", plan.tierId())
                        .addValue("cycle", plan.cycle())
                        .addValue("startsAt", Timestamp.from(period.startsAt()))
                        .addValue("renewsAt", Timestamp.from(period.renewsAt()))
                        .addValue("tx", transactionId)
                        .addValue("settledAt", Timestamp.from(settledAt))
                        .addValue("now", Timestamp.from(now)));
    }

    /** Records the purchase as the next-cycle plan while no other payment is recorded. */
    int recordNext(UUID userId, PlanItemRef plan, UUID transactionId, Instant now) {
        return jdbc.update("""
                UPDATE member_subscription SET pending_tier_id = :tier, pending_billing_cycle = :cycle,
                    pending_transaction_id = :tx, updated_at = :now
                WHERE user_id = :userId AND pending_transaction_id IS NULL AND granted_from_transaction_id <> :tx
                """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("tier", plan.tierId())
                        .addValue("cycle", plan.cycle())
                        .addValue("tx", transactionId)
                        .addValue("now", Timestamp.from(now)));
    }

    /** The recorded next-cycle plan of this member when its stored period has begun at {@code now}. */
    Optional<DuePending> findDuePending(UUID userId, Instant now) {
        return jdbc.query("""
                SELECT s.tier_id AS from_tier, s.status AS from_status, s.pending_tier_id, s.pending_billing_cycle,
                    s.pending_transaction_id, p.period_starts_at, p.period_renews_at
                FROM member_subscription s
                JOIN payment_transaction p ON p.id = s.pending_transaction_id
                WHERE s.user_id = :userId AND p.period_starts_at <= :now
                """,
                new MapSqlParameterSource("userId", userId).addValue("now", Timestamp.from(now)),
                (rs, row) -> new DuePending(rs.getObject("pending_transaction_id", UUID.class),
                        rs.getString("pending_tier_id"), rs.getString("pending_billing_cycle"),
                        instant(rs.getTimestamp("period_starts_at")), instant(rs.getTimestamp("period_renews_at")),
                        rs.getString("from_tier"), rs.getString("from_status"))).stream().findFirst();
    }

    /** Makes the recorded plan the held one, only while the holding still records that payment as next. */
    int activate(UUID userId, DuePending due, Instant now) {
        return jdbc.update("""
                UPDATE member_subscription SET tier_id = :tier, billing_cycle = :cycle, status = 'active',
                    started_at = :startsAt, renews_at = :renewsAt, granted_from_transaction_id = :tx,
                    pending_tier_id = NULL, pending_billing_cycle = NULL, pending_transaction_id = NULL,
                    updated_at = :now
                WHERE user_id = :userId AND pending_transaction_id = :tx
                """,
                new MapSqlParameterSource("userId", userId)
                        .addValue("tier", due.tierId())
                        .addValue("cycle", due.cycle())
                        .addValue("startsAt", Timestamp.from(due.startsAt()))
                        .addValue("renewsAt", Timestamp.from(due.renewsAt()))
                        .addValue("tx", due.transactionId())
                        .addValue("now", Timestamp.from(now)));
    }

    /** Members whose next-cycle period has begun, the earliest period first. */
    List<UUID> dueHoldings(Instant now, int limit) {
        return jdbc.queryForList("""
                SELECT s.user_id FROM member_subscription s
                JOIN payment_transaction p ON p.id = s.pending_transaction_id
                WHERE p.period_starts_at <= :now
                ORDER BY p.period_starts_at, s.user_id
                LIMIT :limit
                """,
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", limit), UUID.class);
    }

    /** Appends one history entry; the from and to values are null where there is none or nothing moved. */
    void appendEvent(UUID userId, UUID transactionId, String fromTier, String toTier, String fromStatus,
            String toStatus, String reason, Instant now) {
        jdbc.update("""
                INSERT INTO member_subscription_event (id, user_id, transaction_id, from_tier_id, to_tier_id,
                    from_status, to_status, reason, occurred_at)
                VALUES (:id, :userId, :tx, :fromTier, :toTier, :fromStatus, :toStatus, :reason, :now)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("userId", userId)
                        .addValue("tx", transactionId)
                        .addValue("fromTier", fromTier)
                        .addValue("toTier", toTier)
                        .addValue("fromStatus", fromStatus)
                        .addValue("toStatus", toStatus)
                        .addValue("reason", reason)
                        .addValue("now", Timestamp.from(now)));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
