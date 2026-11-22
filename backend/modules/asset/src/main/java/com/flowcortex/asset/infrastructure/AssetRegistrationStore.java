package com.flowcortex.asset.infrastructure;

import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RegistrationStatus;
import com.flowcortex.asset.domain.RetryPlan;
import com.flowcortex.asset.domain.SourceType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The durable registration queue. Every state change is one SQL statement, so concurrent
 * claimers are arbitrated by the database: a row is leased with a fresh token, and only that
 * token can finish or retry it.
 */
@Repository
public class AssetRegistrationStore {

    private static final String COLUMNS = "id, source_type, source_id, owner_id, status, attempts, lease_token";

    private static final RowMapper<RegistrationRow> ROWS = (rs, n) -> new RegistrationRow(
            rs.getObject("id", UUID.class),
            SourceType.fromColumn(rs.getString("source_type")),
            rs.getObject("source_id", UUID.class),
            rs.getObject("owner_id", UUID.class),
            RegistrationStatus.fromColumn(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getObject("lease_token", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AssetRegistrationStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Queues the source as pending and due now; a source that already has a row keeps it untouched. */
    public void upsertPending(SourceType sourceType, UUID sourceId, UUID ownerId) {
        Timestamp now = now();
        jdbc.update("INSERT INTO space_asset_registration "
                + "(id, source_type, source_id, owner_id, status, attempts, next_attempt_at, created_at, updated_at) "
                + "VALUES (:id, :type, :source, :owner, 'pending', 0, :now, :now, :now) "
                + "ON CONFLICT (source_type, source_id) DO NOTHING",
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("type", sourceType.column())
                        .addValue("source", sourceId)
                        .addValue("owner", ownerId)
                        .addValue("now", now));
    }

    /** Leases the source's row when it is pending and not under a live lease; empty otherwise. */
    public Optional<RegistrationRow> claim(SourceType sourceType, UUID sourceId) {
        List<RegistrationRow> claimed = jdbc.query("UPDATE space_asset_registration "
                + "SET lease_until = :leaseUntil, lease_token = :token, updated_at = :now "
                + "WHERE source_type = :type AND source_id = :source AND status = 'pending' "
                + "AND (lease_until IS NULL OR lease_until < :now) RETURNING " + COLUMNS,
                new MapSqlParameterSource("type", sourceType.column())
                        .addValue("source", sourceId)
                        .addValue("token", UUID.randomUUID())
                        .addValue("now", now())
                        .addValue("leaseUntil", Timestamp.from(clock.instant().plus(RetryPlan.LEASE))),
                ROWS);
        return claimed.stream().findFirst();
    }

    /** Leases up to {@code limit} pending rows whose retry time has come and whose lease is free or lapsed. */
    public List<RegistrationRow> claimDue(int limit) {
        return jdbc.query("UPDATE space_asset_registration "
                + "SET lease_until = :leaseUntil, lease_token = gen_random_uuid(), updated_at = :now "
                + "WHERE id IN (SELECT id FROM space_asset_registration "
                + "WHERE status = 'pending' AND next_attempt_at <= :now "
                + "AND (lease_until IS NULL OR lease_until < :now) "
                + "ORDER BY next_attempt_at, created_at LIMIT :limit FOR UPDATE SKIP LOCKED) "
                + "RETURNING " + COLUMNS,
                new MapSqlParameterSource("now", now())
                        .addValue("leaseUntil", Timestamp.from(clock.instant().plus(RetryPlan.LEASE)))
                        .addValue("limit", limit),
                ROWS);
    }

    /**
     * Hands claimed rows back untried: the lease is cleared by token and neither the attempts nor the retry time
     * change, so the next pass takes them as if they had never been claimed.
     *
     * @return how many rows were released; a row whose token is no longer the claimant's is left alone
     */
    public int release(List<RegistrationRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE space_asset_registration "
                + "SET lease_until = NULL, lease_token = NULL, updated_at = :now "
                + "WHERE lease_token IN (:tokens)",
                new MapSqlParameterSource("now", now())
                        .addValue("tokens", rows.stream().map(RegistrationRow::leaseToken).toList()));
    }

    /**
     * Ends the row as {@code DONE} or {@code SKIPPED}, clearing its lease.
     *
     * @return false when the row's token is no longer the claimant's, in which case nothing changed
     */
    public boolean finish(RegistrationRow row, RegistrationStatus status) {
        if (status != RegistrationStatus.DONE && status != RegistrationStatus.SKIPPED) {
            throw new IllegalArgumentException("A row finishes as done or skipped, not " + status);
        }
        return jdbc.update("UPDATE space_asset_registration "
                + "SET status = :status, last_error_code = NULL, lease_until = NULL, lease_token = NULL, "
                + "updated_at = :now WHERE id = :id AND lease_token = :token",
                new MapSqlParameterSource("status", status.column())
                        .addValue("now", now())
                        .addValue("id", row.id())
                        .addValue("token", row.leaseToken())) == 1;
    }

    /**
     * Records a failed attempt: back to pending after the plan's wait, or stuck once the attempt
     * budget is spent.
     *
     * @return false when the row's token is no longer the claimant's, in which case nothing changed
     */
    public boolean retry(RegistrationRow row, String code) {
        int failures = row.attempts() + 1;
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource("code", code)
                .addValue("attempts", failures)
                .addValue("now", Timestamp.from(now))
                .addValue("id", row.id())
                .addValue("token", row.leaseToken());
        String sql;
        if (RetryPlan.after(failures) instanceof RetryPlan.Pending pending) {
            params.addValue("next", Timestamp.from(now.plus(pending.delay())));
            sql = "UPDATE space_asset_registration SET status = 'pending', attempts = :attempts, "
                    + "last_error_code = :code, next_attempt_at = :next, lease_until = NULL, lease_token = NULL, "
                    + "updated_at = :now WHERE id = :id AND lease_token = :token";
        } else {
            sql = "UPDATE space_asset_registration SET status = 'stuck', attempts = :attempts, "
                    + "last_error_code = :code, next_attempt_at = NULL, lease_until = NULL, lease_token = NULL, "
                    + "updated_at = :now WHERE id = :id AND lease_token = :token";
        }
        return jdbc.update(sql, params) == 1;
    }

    /** Deletes up to {@code limit} finished rows older than the retention; pending and stuck rows are never touched. */
    public int cleanup(int limit) {
        return jdbc.update("DELETE FROM space_asset_registration WHERE id IN ("
                + "SELECT id FROM space_asset_registration WHERE status IN ('done', 'skipped') "
                + "AND updated_at < :cutoff LIMIT :limit)",
                new MapSqlParameterSource("cutoff", Timestamp.from(clock.instant().minus(RetryPlan.RETENTION)))
                        .addValue("limit", limit));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
