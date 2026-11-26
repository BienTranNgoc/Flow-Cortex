package com.flowcortex.asset.infrastructure;

import com.flowcortex.asset.domain.AssetCursor;
import com.flowcortex.asset.domain.AssetGroupKeys;
import com.flowcortex.asset.domain.AssetQuery.Filters;
import com.flowcortex.asset.domain.AssetQuery.GroupsQuery;
import com.flowcortex.asset.domain.AssetQuery.Kind;
import com.flowcortex.asset.domain.AssetQuery.ListQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The library's reads, one method per statement. Every statement is scoped to one space through
 * {@link AssetSql#listable} (usage excepted) and binds every member-supplied value as a parameter.
 * {@code mark} is the walk's snapshot instant: the caller fixes it, so the statements never read the
 * database clock.
 */
@Repository
public class AssetLibraryQueries {

    private static final int TAG_LIMIT = 100;

    private static final RowMapper<AssetRow> ITEM = AssetLibraryQueries::item;

    private final NamedParameterJdbcTemplate jdbc;

    public AssetLibraryQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One asset as the library lists it; {@code bucket} and {@code objectKey} stay inside the server. */
    public record AssetRow(UUID id, String kind, String title, String source, String mimeType, long bytes,
            Instant createdAt, UUID runId, UUID workflowId, String workflowName, boolean favorite, Instant missingAt,
            String bucket, String objectKey) {

        public boolean missing() {
            return missingAt != null;
        }
    }

    /** What the counts statement answers: under every filter except kind, plus the total under all of them. */
    public record Counts(long all, long video, long image, long audio, long favorite, long total) {
    }

    /** One group of assets and its first items in the requested sort. */
    public record GroupRow(String key, long count, long runCount, Instant latestAt, String workflowName,
            List<AssetRow> items) {
    }

    /** The assets and bytes of one kind. */
    public record KindUsage(String kind, long count, long bytes) {
    }

    /** A tag in use, labelled as first written. */
    public record TagCount(String label, long count) {
    }

    /** Counts under every filter except kind; {@code total} also applies the kind filter. */
    public Counts counts(UUID spaceId, Filters filters, Instant mark) {
        MapSqlParameterSource params = AssetSql.forSpace(spaceId);
        String where = AssetSql.filters(filters, mark, "a", false, params);
        String selected = filters.kinds().isEmpty() ? "count(*)" : "count(*) FILTER (WHERE a.kind IN (:kinds))";
        if (!filters.kinds().isEmpty()) {
            params.addValue("kinds", filters.kinds().stream().map(Kind::value).toList());
        }
        return jdbc.queryForObject("SELECT count(*) AS all_count, "
                + "count(*) FILTER (WHERE a.kind = 'video') AS video_count, "
                + "count(*) FILTER (WHERE a.kind = 'image') AS image_count, "
                + "count(*) FILTER (WHERE a.kind = 'audio') AS audio_count, "
                + "count(*) FILTER (WHERE a.favorite) AS favorite_count, "
                + selected + " AS total_count "
                + "FROM space_asset a WHERE " + AssetSql.listable("a") + where, params,
                (rs, row) -> new Counts(rs.getLong("all_count"), rs.getLong("video_count"),
                        rs.getLong("image_count"), rs.getLong("audio_count"), rs.getLong("favorite_count"),
                        rs.getLong("total_count")));
    }

    /**
     * The page after {@code cursor} (the first page when null), one row longer than the page size so the
     * caller can tell whether another page follows.
     */
    public List<AssetRow> page(UUID spaceId, ListQuery query, AssetCursor cursor, Instant mark) {
        MapSqlParameterSource params = AssetSql.forSpace(spaceId);
        String where = AssetSql.filters(query.filters(), mark, "a", true, params);
        String keyset = AssetSql.after(cursor, "a", params);
        params.addValue("limit", query.pageSize() + 1);
        return jdbc.query("SELECT " + AssetSql.itemColumns("a") + " FROM space_asset a WHERE "
                + AssetSql.listable("a") + where + keyset + " ORDER BY " + AssetSql.orderBy(query.sort(), "a")
                + " LIMIT :limit", params, ITEM);
    }

    /** The tag labels of each asset, in the order they were written; an asset without tags is absent. */
    public Map<UUID, List<String>> tagsOf(UUID spaceId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = AssetSql.forSpace(spaceId).addValue("ids", assetIds);
        Map<UUID, List<String>> tags = new HashMap<>();
        jdbc.query("SELECT t.asset_id, t.label FROM space_asset_tag t JOIN space_asset a ON a.id = t.asset_id "
                + "WHERE " + AssetSql.listable("a") + " AND t.asset_id IN (:ids) ORDER BY t.created_at, t.tag_key",
                params, rs -> {
                    tags.computeIfAbsent(rs.getObject("asset_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getString("label"));
                });
        return tags;
    }

    /**
     * One page of groups, newest asset first then key descending, each with its first
     * {@code itemsPerGroup} items in the requested sort.
     */
    public List<GroupRow> groups(UUID spaceId, GroupsQuery query, Instant mark) {
        MapSqlParameterSource params = AssetSql.forSpace(spaceId);
        String where = AssetSql.filters(query.filters(), mark, "a", true, params);
        params.addValue("limit", query.pageSize());
        params.addValue("offset", (long) (query.page() - 1) * query.pageSize());
        params.addValue("items", query.itemsPerGroup());
        String key = AssetGroupKeys.keyExpression(query.groupBy(), "a");
        Map<String, GroupRow> groups = new LinkedHashMap<>();
        jdbc.query("WITH keyed AS (SELECT " + AssetSql.itemColumns("a") + ", " + key + " AS group_key "
                + "FROM space_asset a WHERE " + AssetSql.listable("a") + where + "), "
                + "grouped AS (SELECT group_key, count(*) AS group_count, count(DISTINCT run_id) AS group_runs, "
                + "max(created_at) AS group_latest, "
                + "(array_agg(workflow_name ORDER BY created_at DESC, id DESC) "
                + "FILTER (WHERE workflow_name IS NOT NULL))[1] AS group_workflow_name "
                + "FROM keyed GROUP BY group_key), "
                + "paged AS (SELECT * FROM grouped ORDER BY group_latest DESC, group_key DESC "
                + "LIMIT :limit OFFSET :offset), "
                + "ranked AS (SELECT k.*, row_number() OVER (PARTITION BY k.group_key ORDER BY "
                + AssetSql.orderBy(query.sort(), "k") + ") AS rn FROM keyed k "
                + "JOIN paged p ON p.group_key = k.group_key) "
                + "SELECT p.group_key, p.group_count, p.group_runs, p.group_latest, p.group_workflow_name, "
                + AssetSql.itemColumns("r") + " FROM paged p JOIN ranked r ON r.group_key = p.group_key "
                + "WHERE r.rn <= :items ORDER BY p.group_latest DESC, p.group_key DESC, r.rn", params,
                (ResultSet rs) -> {
                    String groupKey = rs.getString("group_key");
                    GroupRow group = groups.get(groupKey);
                    if (group == null) {
                        group = new GroupRow(groupKey, rs.getLong("group_count"), rs.getLong("group_runs"),
                                instant(rs, "group_latest"), rs.getString("group_workflow_name"), new ArrayList<>());
                        groups.put(groupKey, group);
                    }
                    group.items().add(item(rs, 0));
                });
        return List.copyOf(groups.values());
    }

    /** How many groups the filters produce, whatever the page. */
    public long groupCount(UUID spaceId, GroupsQuery query, Instant mark) {
        MapSqlParameterSource params = AssetSql.forSpace(spaceId);
        String where = AssetSql.filters(query.filters(), mark, "a", true, params);
        Long count = jdbc.queryForObject("SELECT count(DISTINCT " + AssetGroupKeys.keyExpression(query.groupBy(), "a")
                + ") FROM space_asset a WHERE " + AssetSql.listable("a") + where, params, Long.class);
        return count == null ? 0 : count;
    }

    /** Assets and bytes per kind over every row of the space, missing ones and the Trash included. */
    public List<KindUsage> usage(UUID spaceId) {
        return jdbc.query("SELECT kind, count(*) AS asset_count, COALESCE(sum(bytes), 0) AS total_bytes "
                + "FROM space_asset WHERE space_id = :spaceId GROUP BY kind", AssetSql.forSpace(spaceId),
                (rs, row) -> new KindUsage(rs.getString("kind"), rs.getLong("asset_count"),
                        rs.getLong("total_bytes")));
    }

    /** The tags on the space's assets, one per folded key, most used first then oldest first. */
    public List<TagCount> tags(UUID spaceId) {
        MapSqlParameterSource params = AssetSql.forSpace(spaceId).addValue("limit", TAG_LIMIT);
        return jdbc.query("SELECT (array_agg(t.label ORDER BY t.created_at, t.label))[1] AS label, "
                + "count(*) AS tag_count, min(t.created_at) AS first_used "
                + "FROM space_asset_tag t JOIN space_asset a ON a.id = t.asset_id WHERE " + AssetSql.listable("a")
                + " GROUP BY t.tag_key ORDER BY tag_count DESC, first_used ASC, t.tag_key LIMIT :limit", params,
                (rs, row) -> new TagCount(rs.getString("label"), rs.getLong("tag_count")));
    }

    /** One asset of the space, regardless of the mark; empty for an unknown id and for another space's. */
    public Optional<AssetRow> one(UUID spaceId, UUID id) {
        MapSqlParameterSource params = AssetSql.forSpace(spaceId).addValue("id", id);
        return jdbc.query("SELECT " + AssetSql.itemColumns("a") + " FROM space_asset a WHERE "
                + AssetSql.listable("a") + " AND a.id = :id", params, ITEM).stream().findFirst();
    }

    private static AssetRow item(ResultSet rs, int row) throws SQLException {
        return new AssetRow(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                rs.getString("source"), rs.getString("mime_type"), rs.getLong("bytes"), instant(rs, "created_at"),
                rs.getObject("run_id", UUID.class), rs.getObject("workflow_id", UUID.class),
                rs.getString("workflow_name"), rs.getBoolean("favorite"), instant(rs, "missing_at"),
                rs.getString("bucket"), rs.getString("object_key"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
