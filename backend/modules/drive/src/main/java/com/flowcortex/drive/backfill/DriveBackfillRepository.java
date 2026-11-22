package com.flowcortex.drive.backfill;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The per-space migration record; a row exists only once an apply inserted an entry or a reconcile was saved. */
@Repository
public class DriveBackfillRepository {

    /** What a member reads: when entries were first migrated and how many runs and results that took in. */
    public record Record(Instant migratedAt, int runs, int results) {
    }

    private static final String RECORD_APPLY = "INSERT INTO drive_backfill (space_id, owner_id, migrated_at, counts, "
            + "updated_at) VALUES (:spaceId, :ownerId, :now, jsonb_build_object('runs', CAST(:runs AS integer), "
            + "'results', CAST(:results AS integer)), :now) "
            + "ON CONFLICT (space_id) DO UPDATE SET migrated_at = COALESCE(drive_backfill.migrated_at, :now), "
            + "counts = jsonb_build_object("
            + "'runs', COALESCE((drive_backfill.counts ->> 'runs')::integer, 0) + CAST(:runs AS integer), "
            + "'results', COALESCE((drive_backfill.counts ->> 'results')::integer, 0) + CAST(:results AS integer)), "
            + "updated_at = :now";
    private static final String SAVE_RECONCILE = "INSERT INTO drive_backfill (space_id, owner_id, reconciled_at, ok, "
            + "updated_at) VALUES (:spaceId, :ownerId, :now, :ok, :now) "
            + "ON CONFLICT (space_id) DO UPDATE SET reconciled_at = :now, ok = :ok, updated_at = :now";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    DriveBackfillRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Adds what one apply inserted to the space's counts and sets {@code migrated_at} on the first call only. */
    public void recordApply(UUID spaceId, UUID ownerId, int runs, int results) {
        jdbc.update(RECORD_APPLY, params(spaceId, ownerId).addValue("runs", runs).addValue("results", results));
    }

    /** Records that a reconcile compared this space and whether it found no difference. */
    public void saveReconcile(UUID spaceId, UUID ownerId, boolean ok) {
        jdbc.update(SAVE_RECONCILE, params(spaceId, ownerId).addValue("ok", ok));
    }

    /** The space's migration record, empty when no apply ever inserted an entry for it. */
    public Optional<Record> find(UUID spaceId) {
        return jdbc.query("SELECT migrated_at, counts ->> 'runs' AS runs, counts ->> 'results' AS results "
                + "FROM drive_backfill WHERE space_id = :spaceId AND migrated_at IS NOT NULL",
                new MapSqlParameterSource("spaceId", spaceId),
                (rs, row) -> new Record(rs.getTimestamp("migrated_at").toInstant(), rs.getInt("runs"),
                        rs.getInt("results"))).stream().findFirst();
    }

    private MapSqlParameterSource params(UUID spaceId, UUID ownerId) {
        return new MapSqlParameterSource().addValue("spaceId", spaceId).addValue("ownerId", ownerId)
                .addValue("now", Timestamp.from(clock.instant()));
    }
}
