package com.flowcortex.billing.reconcile;

import com.flowcortex.billing.ledger.MoneyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the reconciliation schedule, the disagreements found, and the time a payment was last asked
 * about. Every write to a schedule row is conditional on the row still being open, so a late finisher of a
 * pass changes nothing.
 */
@Repository
class ReconcileRepository {

    private static final String OPEN = "resolved_at IS NULL AND escalated_at IS NULL";
    private static final String ASKED_COLUMNS =
            "SELECT id, provider, order_ref, status, created_at, amount_vnd FROM payment_transaction";
    private static final Duration DAILY_GAP = Duration.ofHours(24);

    /** A schedule row that is due. */
    record DueRow(UUID id, UUID transactionId, int attempts) {
    }

    /** The payment as an item needs it to decide what to ask. */
    record AskedPayment(UUID id, String provider, String orderRef, MoneyStatus status, Instant openedAt,
            long amountVnd) {
    }

    /** What the unresolved gauges report. */
    record Measurement(long unresolved, long oldestAgeSeconds, long escalated) {
    }

    /** One disagreement between our record and a provider's answer. */
    record DriftRecord(UUID transactionId, DriftClass driftClass, String pass, String provider, String ourStatus,
            String providerStatus, String providerResultCode, long ourAmountVnd, Long providerAmountVnd,
            String evidenceRef, String resolution, boolean needsPerson, Instant detectedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    ReconcileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gives every pending payment opened at least {@code floor} and less than the ceiling ago, and not yet
     * enrolled, a schedule row with no asks and its first ask due at opening plus the floor.
     */
    int enrolDue(Duration floor, Instant now) {
        return jdbc.update("""
                INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts,
                    next_sweep_at, created_at, updated_at)
                SELECT gen_random_uuid(), t.id, t.created_at, 0, t.created_at + make_interval(secs => :floorSeconds),
                    :now, :now
                FROM payment_transaction t
                WHERE t.status = 'pending' AND t.created_at <= :oldEnough AND t.created_at > :windowStart
                ON CONFLICT (transaction_id) DO NOTHING
                """,
                new MapSqlParameterSource("floorSeconds", floor.toMillis() / 1000.0)
                        .addValue("now", Timestamp.from(now))
                        .addValue("oldEnough", Timestamp.from(now.minus(floor)))
                        .addValue("windowStart", Timestamp.from(now.minus(ReconcileLadder.CEILING))));
    }

    /** Open rows whose next ask is at or before {@code now}, earliest due first. */
    List<DueRow> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT id, transaction_id, attempts FROM payment_reconciliation_schedule
                WHERE resolved_at IS NULL AND escalated_at IS NULL AND next_sweep_at <= :now
                ORDER BY next_sweep_at, id LIMIT :limit
                """,
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", limit),
                (rs, row) -> new DueRow(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                        rs.getInt("attempts")));
    }

    /** The row climbs one rung after an ask: {@code attempts} is the new count. */
    boolean advance(UUID id, int attempts, Instant lastSweptAt, Instant nextSweepAt, String outcome, Instant now) {
        return jdbc.update("UPDATE payment_reconciliation_schedule SET attempts = :attempts, "
                + "last_swept_at = :lastSweptAt, next_sweep_at = :nextSweepAt, last_outcome = :outcome, "
                + "updated_at = :now WHERE id = :id AND " + OPEN,
                new MapSqlParameterSource("id", id).addValue("attempts", attempts)
                        .addValue("lastSweptAt", Timestamp.from(lastSweptAt))
                        .addValue("nextSweepAt", Timestamp.from(nextSweepAt)).addValue("outcome", outcome)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /** The row waits for {@code nextSweepAt} without spending a rung. */
    boolean defer(UUID id, Instant nextSweepAt, String outcome, Instant now) {
        return jdbc.update("UPDATE payment_reconciliation_schedule SET next_sweep_at = :nextSweepAt, "
                + "last_outcome = :outcome, updated_at = :now WHERE id = :id AND " + OPEN,
                new MapSqlParameterSource("id", id).addValue("nextSweepAt", Timestamp.from(nextSweepAt))
                        .addValue("outcome", outcome).addValue("now", Timestamp.from(now))) == 1;
    }

    /** The row leaves the schedule; an answer that was asked for counts as one more ask. */
    boolean resolve(UUID id, String outcome, boolean asked, Instant now) {
        return jdbc.update("UPDATE payment_reconciliation_schedule SET resolved_at = :now, next_sweep_at = NULL, "
                + "last_outcome = :outcome, attempts = attempts + :asks, "
                + "last_swept_at = CASE WHEN :asked THEN :now ELSE last_swept_at END, updated_at = :now "
                + "WHERE id = :id AND " + OPEN,
                new MapSqlParameterSource("id", id).addValue("outcome", outcome).addValue("asked", asked)
                        .addValue("asks", asked ? 1 : 0).addValue("now", Timestamp.from(now))) == 1;
    }

    /** The row is held for a person and is never taken again. */
    boolean escalate(UUID id, String reason, String outcome, boolean asked, Instant now) {
        return jdbc.update("UPDATE payment_reconciliation_schedule SET escalated_at = :now, next_sweep_at = NULL, "
                + "escalation_reason = :reason, last_outcome = :outcome, attempts = attempts + :asks, "
                + "last_swept_at = CASE WHEN :asked THEN :now ELSE last_swept_at END, updated_at = :now "
                + "WHERE id = :id AND " + OPEN,
                new MapSqlParameterSource("id", id).addValue("reason", reason).addValue("outcome", outcome)
                        .addValue("asked", asked).addValue("asks", asked ? 1 : 0)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /** Writes the record unless one exists for the payment and evidence; true when it was written. */
    boolean insertDrift(DriftRecord drift) {
        return jdbc.update("""
                INSERT INTO payment_drift_record (id, transaction_id, drift_class, pass, provider, our_status,
                    provider_status, provider_result_code, our_amount_vnd, provider_amount_vnd, evidence_ref,
                    resolution, needs_person, detected_at)
                VALUES (:id, :transactionId, :driftClass, :pass, :provider, :ourStatus, :providerStatus,
                    :resultCode, :ourAmount, :providerAmount, :evidenceRef, :resolution, :needsPerson, :detectedAt)
                ON CONFLICT (transaction_id, evidence_ref) DO NOTHING
                """,
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("transactionId", drift.transactionId())
                        .addValue("driftClass", drift.driftClass().wire())
                        .addValue("pass", drift.pass())
                        .addValue("provider", drift.provider())
                        .addValue("ourStatus", drift.ourStatus())
                        .addValue("providerStatus", drift.providerStatus())
                        .addValue("resultCode", drift.providerResultCode())
                        .addValue("ourAmount", drift.ourAmountVnd())
                        .addValue("providerAmount", drift.providerAmountVnd())
                        .addValue("evidenceRef", drift.evidenceRef())
                        .addValue("resolution", drift.resolution())
                        .addValue("needsPerson", drift.needsPerson())
                        .addValue("detectedAt", Timestamp.from(drift.detectedAt()))) == 1;
    }

    /** Records that either pass asked about the payment at {@code now}. */
    void markChecked(UUID transactionId, Instant now) {
        jdbc.update("UPDATE payment_transaction SET reconciled_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", transactionId).addValue("now", Timestamp.from(now)));
    }

    Optional<AskedPayment> findForAsking(UUID transactionId) {
        return jdbc.query(ASKED_COLUMNS + " WHERE id = :id", new MapSqlParameterSource("id", transactionId),
                ReconcileRepository::askedPayment).stream().findFirst();
    }

    /**
     * Payments the daily pass may ask about: opened between the floor and the ceiling ago, of one of
     * {@code providers}, with no open or escalated schedule row, never checked or last checked a day or more
     * ago; never-checked first, then least recently checked, then oldest opening.
     */
    List<AskedPayment> dailyCandidates(Duration floor, Instant now, Set<String> providers, int limit) {
        if (providers.isEmpty()) {
            return List.of();
        }
        return jdbc.query(ASKED_COLUMNS + """
                 WHERE created_at <= :oldEnough AND created_at > :windowStart AND provider IN (:providers)
                   AND (reconciled_at IS NULL OR reconciled_at <= :checkedBefore)
                   AND NOT EXISTS (SELECT 1 FROM payment_reconciliation_schedule s
                       WHERE s.transaction_id = payment_transaction.id
                         AND (s.escalated_at IS NOT NULL OR s.resolved_at IS NULL))
                 ORDER BY reconciled_at NULLS FIRST, created_at, id LIMIT :limit
                """,
                new MapSqlParameterSource("oldEnough", Timestamp.from(now.minus(floor)))
                        .addValue("windowStart", Timestamp.from(now.minus(ReconcileLadder.CEILING)))
                        .addValue("providers", providers)
                        .addValue("checkedBefore", Timestamp.from(now.minus(DAILY_GAP)))
                        .addValue("limit", limit),
                ReconcileRepository::askedPayment);
    }

    /** Locks the payment row until the surrounding transaction ends. Call it inside a transaction. */
    void lockPayment(UUID transactionId) {
        jdbc.query("SELECT id FROM payment_transaction WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", transactionId), (rs, row) -> rs.getObject("id", UUID.class));
    }

    boolean hasOpenRow(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment_reconciliation_schedule
                    WHERE transaction_id = :id AND resolved_at IS NULL AND escalated_at IS NULL)
                """, new MapSqlParameterSource("id", transactionId), Boolean.class));
    }

    /**
     * Holds the payment's schedule row for a person whatever it held: creates it escalated, or escalates an
     * open or resolved one and clears its next ask. A row already escalated is left as it is.
     */
    void escalateForDaily(UUID transactionId, Instant openedAt, Instant now) {
        jdbc.update("""
                INSERT INTO payment_reconciliation_schedule (id, transaction_id, initiated_at, attempts,
                    last_swept_at, escalated_at, escalation_reason, last_outcome, created_at, updated_at)
                VALUES (:id, :transactionId, :openedAt, 1, :now, :now, 'discrepancy', 'discrepancy', :now, :now)
                ON CONFLICT (transaction_id) DO UPDATE SET escalated_at = :now, escalation_reason = 'discrepancy',
                    last_outcome = 'discrepancy', next_sweep_at = NULL, resolved_at = NULL, last_swept_at = :now,
                    attempts = payment_reconciliation_schedule.attempts + 1, updated_at = :now
                WHERE payment_reconciliation_schedule.escalated_at IS NULL
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("transactionId", transactionId)
                        .addValue("openedAt", Timestamp.from(openedAt)).addValue("now", Timestamp.from(now)));
    }

    private static AskedPayment askedPayment(ResultSet rs, int row) throws SQLException {
        return new AskedPayment(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("order_ref"),
                MoneyStatus.fromWire(rs.getString("status")).orElseThrow(
                        () -> new IllegalStateException("A stored payment has an unknown status")),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("amount_vnd"));
    }

    /** Pending payments opened at least {@code floor} ago, how long the oldest has waited, and escalated rows. */
    Measurement measure(Duration floor, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("oldEnough", Timestamp.from(now.minus(floor)));
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM payment_transaction
                            WHERE status = 'pending' AND created_at <= :oldEnough) AS unresolved,
                       (SELECT COALESCE(floor(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - min(created_at)))), 0)
                            FROM payment_transaction
                            WHERE status = 'pending' AND created_at <= :oldEnough) AS oldest,
                       (SELECT count(*) FROM payment_reconciliation_schedule WHERE escalated_at IS NOT NULL) AS escalated
                """,
                params,
                (rs, row) -> new Measurement(rs.getLong("unresolved"), rs.getLong("oldest"), rs.getLong("escalated")));
    }
}
