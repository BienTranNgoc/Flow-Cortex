package com.flowcortex.drive.memory;

import com.flowcortex.drive.domain.BackfillReconciler.Key;
import com.flowcortex.drive.domain.BackfillReconciler.SpaceKeys;
import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.drive.domain.MemoryFilter;
import com.flowcortex.drive.domain.MemoryFilter.SizeBucket;
import com.flowcortex.drive.domain.MemoryProjector.Entry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Memory rows of every space; each query that serves a member is scoped by the space id it is given. */
@Repository
public class MemoryResultRepository {

    /** The member-controlled flags of an entry; the column name never comes from a request. */
    public enum Flag {
        PINNED("pinned"), HIDDEN("hidden");

        private final String column;

        Flag(String column) {
            this.column = column;
        }
    }

    /** What {@link #replaceRun} did to the run's rows. */
    public record Replaced(int inserted, int updated, int removed) {
    }

    /** A stored Memory row. */
    public record Stored(UUID id, UUID spaceId, UUID ownerId, UUID runId, UUID runStepId, int stepOrder,
            String resultKey, ContentType kind, String title, String previewText, String bucket, String objectKey,
            String fileName, String mimeType, long sizeBytes, Instant resultAt, boolean pinned, boolean hidden,
            Instant createdAt, Instant updatedAt) {
    }

    /** One page of a filtered list with the number of matches across all pages. */
    public record Page(List<Stored> items, long total) {
    }

    private static final String COLUMNS = "id, space_id, owner_id, run_id, run_step_id, step_order, result_key, kind, "
            + "title, preview_text, bucket, object_key, file_name, mime_type, size_bytes, result_at, pinned, hidden, "
            + "created_at, updated_at";
    private static final String PROJECTED = "owner_id, run_id, run_step_id, step_order, kind, title, preview_text, "
            + "bucket, object_key, file_name, mime_type, size_bytes, result_at";
    private static final String UPSERT = "INSERT INTO memory_result (id, space_id, owner_id, run_id, run_step_id, "
            + "step_order, result_key, kind, title, preview_text, bucket, object_key, file_name, mime_type, "
            + "size_bytes, result_at, created_at, updated_at) VALUES (:id, :spaceId, :ownerId, :runId, :stepId, "
            + ":stepOrder, :resultKey, :kind, :title, :preview, :bucket, :objectKey, :fileName, :mime, :size, "
            + ":resultAt, :now, :now) "
            + "ON CONFLICT (space_id, result_key) DO UPDATE SET owner_id = excluded.owner_id, "
            + "run_id = excluded.run_id, run_step_id = excluded.run_step_id, step_order = excluded.step_order, "
            + "kind = excluded.kind, title = excluded.title, preview_text = excluded.preview_text, "
            + "bucket = excluded.bucket, object_key = excluded.object_key, file_name = excluded.file_name, "
            + "mime_type = excluded.mime_type, size_bytes = excluded.size_bytes, result_at = excluded.result_at, "
            + "updated_at = excluded.updated_at "
            + "WHERE (" + qualified("memory_result") + ") IS DISTINCT FROM (" + qualified("excluded") + ") "
            + "RETURNING (xmax = 0) AS inserted";

    private static final RowMapper<Stored> STORED = MemoryResultRepository::stored;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    MemoryResultRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Makes the run's rows exactly {@code entries}: new keys are inserted, changed projections are updated
     * (an identical row is not touched, not even its {@code updated_at}), the member's flags are never
     * written, and rows of the run whose key is no longer projected are deleted — all in one transaction.
     */
    @Transactional
    public Replaced replaceRun(UUID spaceId, UUID ownerId, UUID runId, List<Entry> entries) {
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = 0;
        int updated = 0;
        for (Entry entry : entries) {
            List<Boolean> outcome = jdbc.query(UPSERT, upsertParams(spaceId, ownerId, runId, entry, now),
                    (rs, row) -> rs.getBoolean("inserted"));
            if (outcome.isEmpty()) {
                continue;
            }
            if (outcome.get(0)) {
                inserted++;
            } else {
                updated++;
            }
        }
        return new Replaced(inserted, updated, deleteStale(runId, entries));
    }

    public Page list(UUID spaceId, MemoryFilter filter) {
        MemoryFilter.Sql sql = filter.where(spaceId);
        long total = jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE " + sql.where(),
                new MapSqlParameterSource(sql.params()), Long.class);
        if (total == 0) {
            return new Page(List.of(), 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource(sql.params())
                .addValue("limit", filter.pageSize()).addValue("offset", filter.offset());
        List<Stored> items = jdbc.query("SELECT " + COLUMNS + " FROM memory_result WHERE " + sql.where()
                + " ORDER BY " + filter.orderBy() + " LIMIT :limit OFFSET :offset", params, STORED);
        return new Page(items, total);
    }

    /** Matches per size bucket under every filter except the size one; every bucket is present. */
    public Map<SizeBucket, Long> bucketCounts(UUID spaceId, MemoryFilter filter) {
        MemoryFilter.Sql sql = filter.whereIgnoringSize(spaceId);
        StringBuilder select = new StringBuilder("SELECT ");
        List<SizeBucket> buckets = List.of(SizeBucket.values());
        for (int i = 0; i < buckets.size(); i++) {
            select.append(i == 0 ? "" : ", ").append("count(*) FILTER (WHERE ").append(buckets.get(i).predicate())
                    .append(") AS b").append(i);
        }
        select.append(" FROM memory_result WHERE ").append(sql.where());
        return jdbc.queryForObject(select.toString(), new MapSqlParameterSource(sql.params()), (rs, row) -> {
            Map<SizeBucket, Long> counts = new EnumMap<>(SizeBucket.class);
            for (int i = 0; i < buckets.size(); i++) {
                counts.put(buckets.get(i), rs.getLong("b" + i));
            }
            return counts;
        });
    }

    /** The newest pinned entries that are not hidden. */
    public List<Stored> pinned(UUID spaceId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM memory_result WHERE space_id = :spaceId AND pinned "
                + "AND NOT hidden ORDER BY result_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource("spaceId", spaceId).addValue("limit", limit), STORED);
    }

    /**
     * Sets one flag and returns the rows matched (0 for another space's or an unknown id). Setting the value
     * a row already holds matches it too and leaves it untouched.
     */
    public int setFlag(UUID spaceId, UUID id, Flag flag, boolean value) {
        return jdbc.update("UPDATE memory_result SET " + flag.column + " = :value, updated_at = CASE WHEN "
                + flag.column + " IS DISTINCT FROM :value THEN :now ELSE updated_at END "
                + "WHERE space_id = :spaceId AND id = :id",
                new MapSqlParameterSource("value", value).addValue("now", Timestamp.from(clock.instant()))
                        .addValue("spaceId", spaceId).addValue("id", id));
    }

    public Optional<Stored> find(UUID spaceId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM memory_result WHERE space_id = :spaceId AND id = :id",
                new MapSqlParameterSource("spaceId", spaceId).addValue("id", id), STORED).stream().findFirst();
    }

    /** The stored keys of the given runs grouped by space, for comparing against what the runs project. */
    public Map<UUID, SpaceKeys> keysBySpace(Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> owners = new LinkedHashMap<>();
        Map<UUID, Set<Key>> keys = new LinkedHashMap<>();
        jdbc.query("SELECT space_id, owner_id, run_id, result_key FROM memory_result WHERE run_id IN (:runIds)",
                new MapSqlParameterSource("runIds", runIds), rs -> {
                    UUID space = rs.getObject("space_id", UUID.class);
                    owners.putIfAbsent(space, rs.getObject("owner_id", UUID.class));
                    keys.computeIfAbsent(space, ignored -> new HashSet<>())
                            .add(new Key(rs.getObject("run_id", UUID.class), rs.getString("result_key")));
                });
        return keys.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> new SpaceKeys(owners.get(entry.getKey()), entry.getValue()), (a, b) -> a,
                LinkedHashMap::new));
    }

    private int deleteStale(UUID runId, List<Entry> entries) {
        MapSqlParameterSource params = new MapSqlParameterSource("runId", runId);
        if (entries.isEmpty()) {
            return jdbc.update("DELETE FROM memory_result WHERE run_id = :runId", params);
        }
        List<String> keys = new ArrayList<>(entries.size());
        entries.forEach(entry -> keys.add(entry.resultKey()));
        return jdbc.update("DELETE FROM memory_result WHERE run_id = :runId AND result_key NOT IN (:keys)",
                params.addValue("keys", keys));
    }

    private static MapSqlParameterSource upsertParams(UUID spaceId, UUID ownerId, UUID runId, Entry entry,
            Timestamp now) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("spaceId", spaceId)
                .addValue("ownerId", ownerId)
                .addValue("runId", runId)
                .addValue("stepId", entry.stepId())
                .addValue("stepOrder", entry.stepOrder(), Types.SMALLINT)
                .addValue("resultKey", entry.resultKey())
                .addValue("kind", entry.kind().wire())
                .addValue("title", entry.title())
                .addValue("preview", entry.previewText(), Types.VARCHAR)
                .addValue("bucket", entry.bucket(), Types.VARCHAR)
                .addValue("objectKey", entry.objectKey(), Types.VARCHAR)
                .addValue("fileName", entry.fileName(), Types.VARCHAR)
                .addValue("mime", entry.mimeType(), Types.VARCHAR)
                .addValue("size", entry.sizeBytes())
                .addValue("resultAt", Timestamp.from(entry.resultAt()))
                .addValue("now", now);
    }

    private static String qualified(String table) {
        StringBuilder out = new StringBuilder();
        for (String column : PROJECTED.split(", ")) {
            out.append(out.isEmpty() ? "" : ", ").append(table).append('.').append(column);
        }
        return out.toString();
    }

    private static Stored stored(ResultSet rs, int row) throws SQLException {
        return new Stored(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
                rs.getObject("owner_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("run_step_id", UUID.class), rs.getInt("step_order"), rs.getString("result_key"),
                ContentType.fromWire(rs.getString("kind")).orElseThrow(), rs.getString("title"),
                rs.getString("preview_text"), rs.getString("bucket"), rs.getString("object_key"),
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                rs.getTimestamp("result_at").toInstant(), rs.getBoolean("pinned"), rs.getBoolean("hidden"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
