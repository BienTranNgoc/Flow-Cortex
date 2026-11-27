package com.flowcortex.asset.infrastructure;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What the library holds for a batch of runs, in one statement whatever the batch size: the run ids go in as
 * one text parameter and come back in the order they were sent. Only the caller's space is read.
 */
@Repository
public class RunResultQueries {

    /** A signable thumbnail source: an image of the run that is not missing. */
    public record ThumbnailRow(UUID id, String title, String mimeType, String bucket, String objectKey) {
    }

    /**
     * One run's summary: its {@code run} assets in the space, their distinct kinds newest first, up to three
     * newest non-missing images, and the status of its output registration ({@code null} when it has no row).
     */
    public record RunResultRow(UUID runId, long count, List<String> kinds, List<ThumbnailRow> thumbnails,
            String registrationStatus) {
    }

    private static final String SQL = "SELECT r.run_id, s.asset_count, s.kinds, reg.status AS registration_status, "
            + "t.id AS thumb_id, t.title AS thumb_title, t.mime_type AS thumb_mime, t.bucket AS thumb_bucket, "
            + "t.object_key AS thumb_key "
            + "FROM unnest(string_to_array(:runIds, ',')::uuid[]) WITH ORDINALITY AS r(run_id, ord) "
            + "CROSS JOIN LATERAL ("
            + "SELECT COALESCE(sum(k.n), 0)::bigint AS asset_count, "
            + "COALESCE(array_agg(k.kind::text ORDER BY k.newest DESC, k.kind), ARRAY[]::text[]) AS kinds "
            + "FROM (SELECT a.kind, count(*) AS n, max(a.created_at) AS newest FROM space_asset a WHERE "
            + AssetSql.listable("a") + " AND a.source = 'run' AND a.run_id = r.run_id GROUP BY a.kind) k) s "
            + "LEFT JOIN LATERAL ("
            + "SELECT i.id, i.title, i.mime_type, i.bucket, i.object_key, i.created_at FROM space_asset i WHERE "
            + AssetSql.listable("i") + " AND i.source = 'run' AND i.run_id = r.run_id AND i.kind = 'image' "
            + "AND i.missing_at IS NULL ORDER BY i.created_at DESC, i.id DESC LIMIT 3) t ON true "
            + "LEFT JOIN space_asset_registration reg ON reg.source_type = 'run_outputs' "
            + "AND reg.source_id = r.run_id AND reg.owner_id = :ownerId "
            + "ORDER BY r.ord, t.created_at DESC, t.id DESC";

    private final NamedParameterJdbcTemplate jdbc;

    public RunResultQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The summaries of {@code runIds}, one per id in the given order; a run the space holds nothing for
     * and has no registration row for answers zero and no status.
     */
    public List<RunResultRow> results(UUID spaceId, UUID ownerId, Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = AssetSql.forSpace(spaceId).addValue("ownerId", ownerId)
                .addValue("runIds", runIds.stream().map(UUID::toString).collect(Collectors.joining(",")));
        Map<UUID, Builder> byRun = new LinkedHashMap<>();
        jdbc.query(SQL, params, rs -> {
            UUID runId = rs.getObject("run_id", UUID.class);
            Builder run = byRun.get(runId);
            if (run == null) {
                run = new Builder(runId, rs.getLong("asset_count"), kinds(rs.getArray("kinds")),
                        rs.getString("registration_status"));
                byRun.put(runId, run);
            }
            UUID thumbnail = rs.getObject("thumb_id", UUID.class);
            if (thumbnail != null) {
                run.thumbnails.add(new ThumbnailRow(thumbnail, rs.getString("thumb_title"),
                        rs.getString("thumb_mime"), rs.getString("thumb_bucket"), rs.getString("thumb_key")));
            }
        });
        return byRun.values().stream().map(Builder::build).toList();
    }

    private static List<String> kinds(Array array) throws SQLException {
        return array == null ? List.of() : List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    private static final class Builder {
        private final UUID runId;
        private final long count;
        private final List<String> kinds;
        private final String registrationStatus;
        private final List<ThumbnailRow> thumbnails = new ArrayList<>();

        private Builder(UUID runId, long count, List<String> kinds, String registrationStatus) {
            this.runId = runId;
            this.count = count;
            this.kinds = kinds;
            this.registrationStatus = registrationStatus;
        }

        private RunResultRow build() {
            return new RunResultRow(runId, count, kinds, List.copyOf(thumbnails), registrationStatus);
        }
    }
}
