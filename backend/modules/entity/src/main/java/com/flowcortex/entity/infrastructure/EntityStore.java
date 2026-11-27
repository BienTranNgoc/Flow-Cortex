package com.flowcortex.entity.infrastructure;

import com.flowcortex.entity.domain.EntityChanges.Change;
import com.flowcortex.entity.domain.EntitySnapshot;
import com.flowcortex.entity.domain.EntityText;
import com.flowcortex.entity.domain.TagLabels;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code space_entity} and its parts, tags and versions. Every read and write is scoped to one space and to live
 * rows, so another space's or a trashed entity's id is simply absent.
 */
@Repository
public class EntityStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() { };

    /** The longest base prefix every generated handle candidate shares, whatever its numeric suffix up to four digits. */
    private static final int HANDLE_STEM = 40;

    private static final String COLUMNS = "e.id, e.space_id, e.kind, e.handle, e.name, e.fields, e.category_label, "
            + "e.version, e.created_by, e.created_at, e.updated_at";

    private static final RowMapper<EntityRow> ROWS = (rs, i) -> new EntityRow(
            rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getString("kind"),
            rs.getString("handle"), rs.getString("name"), JSON.readValue(rs.getString("fields"), OBJECT),
            rs.getString("category_label"), rs.getInt("version"), rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    /** A stored entity without its parts and tags. */
    public record EntityRow(UUID id, UUID spaceId, String kind, String handle, String name,
            Map<String, Object> fields, String categoryLabel, int version, UUID createdBy, Instant createdAt,
            Instant updatedAt) {
    }

    /** A stored part; {@code createdAt} dates the label's use. */
    public record PartRow(UUID id, UUID assetId, String role, String label, int sort, Instant createdAt) {
    }

    /** What a new entity row holds; the version is 1. */
    public record NewEntity(UUID id, UUID spaceId, UUID createdBy, String kind, String handle, String name,
            Map<String, Object> fields, String categoryLabel) {
    }

    /** The list filters, each already reduced to its text key; {@code null} means "no filter". */
    public record EntityFilter(String kind, String qKey, String tagKey, String categoryKey) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public EntityStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Inserts the entity at version 1. Empty when a live entity of the space already holds the handle: no exception
     * is raised, so the surrounding transaction stays usable for another attempt.
     */
    public Optional<UUID> insert(NewEntity entity) {
        Timestamp now = Timestamp.from(clock.instant());
        List<UUID> ids = jdbc.query("INSERT INTO space_entity (id, space_id, kind, handle, name, search_key, fields, "
                + "category_label, category_key, version, created_by, created_at, updated_at) "
                + "VALUES (:id, :space, :kind, :handle, :name, :searchKey, CAST(:fields AS jsonb), :categoryLabel, "
                + ":categoryKey, 1, :by, :now, :now) "
                + "ON CONFLICT (space_id, handle) WHERE deleted_at IS NULL DO NOTHING RETURNING id",
                new MapSqlParameterSource("id", entity.id())
                        .addValue("space", entity.spaceId())
                        .addValue("kind", entity.kind())
                        .addValue("handle", entity.handle())
                        .addValue("name", entity.name())
                        .addValue("searchKey", EntityText.key(entity.name()))
                        .addValue("fields", toJson(entity.fields()))
                        .addValue("categoryLabel", entity.categoryLabel())
                        .addValue("categoryKey", categoryKey(entity.categoryLabel()))
                        .addValue("by", entity.createdBy())
                        .addValue("now", now),
                (rs, i) -> rs.getObject("id", UUID.class));
        return ids.stream().findFirst();
    }

    public Optional<EntityRow> findLive(UUID id, UUID spaceId) {
        return selectOne("", id, spaceId);
    }

    /** As {@link #findLive}, holding the row's lock until the transaction ends. */
    public Optional<EntityRow> lockLive(UUID id, UUID spaceId) {
        return selectOne(" FOR UPDATE", id, spaceId);
    }

    /**
     * Writes the edited content and the new version number.
     *
     * @throws HandleTakenException when a concurrent create took the handle
     */
    public void update(UUID id, int version, String handle, String name, Map<String, Object> fields,
            String categoryLabel) {
        try {
            jdbc.update("UPDATE space_entity SET handle = :handle, name = :name, search_key = :searchKey, "
                    + "fields = CAST(:fields AS jsonb), category_label = :categoryLabel, category_key = :categoryKey, "
                    + "version = :version, updated_at = :now WHERE id = :id",
                    new MapSqlParameterSource("id", id)
                            .addValue("handle", handle)
                            .addValue("name", name)
                            .addValue("searchKey", EntityText.key(name))
                            .addValue("fields", toJson(fields))
                            .addValue("categoryLabel", categoryLabel)
                            .addValue("categoryKey", categoryKey(categoryLabel))
                            .addValue("version", version)
                            .addValue("now", Timestamp.from(clock.instant())));
        } catch (DuplicateKeyException e) {
            throw new HandleTakenException(e);
        }
    }

    /**
     * Makes the entity's parts exactly {@code parts}. A part that keeps its asset, role and label keeps its id and
     * its date, so the label's recency is not reset by an unrelated edit.
     */
    public void replaceParts(UUID entityId, List<EntitySnapshot.Part> parts) {
        Map<List<Object>, Deque<PartRow>> existing = new HashMap<>();
        for (PartRow row : partsOf(List.of(entityId)).getOrDefault(entityId, List.of())) {
            existing.computeIfAbsent(partKey(row.assetId(), row.role(), row.label()), k -> new ArrayDeque<>()).add(row);
        }
        jdbc.update("DELETE FROM space_entity_part WHERE entity_id = :entity", new MapSqlParameterSource("entity", entityId));
        Timestamp now = Timestamp.from(clock.instant());
        List<SqlParameterSource> inserts = new ArrayList<>();
        for (EntitySnapshot.Part part : parts) {
            Deque<PartRow> same = existing.get(partKey(part.assetId(), part.role(), part.label()));
            PartRow kept = same == null ? null : same.poll();
            inserts.add(new MapSqlParameterSource("id", kept == null ? UUID.randomUUID() : kept.id())
                    .addValue("entity", entityId)
                    .addValue("asset", part.assetId())
                    .addValue("role", part.role())
                    .addValue("label", part.label())
                    .addValue("labelKey", part.label() == null ? null : EntityText.key(part.label()))
                    .addValue("sort", part.sort())
                    .addValue("created", kept == null ? now : Timestamp.from(kept.createdAt())));
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO space_entity_part (id, entity_id, asset_id, role, label, label_key, sort, "
                    + "created_at) VALUES (:id, :entity, :asset, :role, :label, :labelKey, :sort, :created)",
                    inserts.toArray(SqlParameterSource[]::new));
        }
    }

    /** Makes the entity's tags exactly {@code tags}; a tag that stays keeps its date and its spelling. */
    public void replaceTags(UUID entityId, List<TagLabels.Tag> tags) {
        List<String> keys = tags.stream().map(TagLabels.Tag::key).toList();
        jdbc.update("DELETE FROM space_entity_tag WHERE entity_id = :entity "
                + (keys.isEmpty() ? "" : "AND tag_key NOT IN (:keys)"),
                new MapSqlParameterSource("entity", entityId).addValue("keys", keys));
        Timestamp now = Timestamp.from(clock.instant());
        List<SqlParameterSource> inserts = tags.stream()
                .<SqlParameterSource>map(tag -> new MapSqlParameterSource("entity", entityId)
                        .addValue("key", tag.key()).addValue("label", tag.label()).addValue("created", now))
                .toList();
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO space_entity_tag (entity_id, tag_key, tag_label, created_at) "
                    + "VALUES (:entity, :key, :label, :created) ON CONFLICT (entity_id, tag_key) DO NOTHING",
                    inserts.toArray(SqlParameterSource[]::new));
        }
    }

    public void appendVersion(UUID entityId, int version, EntitySnapshot snapshot, List<Change> changes) {
        jdbc.update("INSERT INTO space_entity_version (entity_id, version, snapshot, changes, created_at) "
                + "VALUES (:entity, :version, CAST(:snapshot AS jsonb), CAST(:changes AS jsonb), :now)",
                new MapSqlParameterSource("entity", entityId)
                        .addValue("version", version)
                        .addValue("snapshot", toJson(snapshotJson(snapshot)))
                        .addValue("changes", toJson(changesJson(changes)))
                        .addValue("now", Timestamp.from(clock.instant())));
    }

    /** Moves a live entity to the trash state; false when there is no such live entity in the space. */
    public boolean trash(UUID id, UUID spaceId, UUID deletedBy) {
        return jdbc.update("UPDATE space_entity SET deleted_at = :now, deleted_by = :by "
                + "WHERE id = :id AND space_id = :space AND deleted_at IS NULL",
                new MapSqlParameterSource("id", id).addValue("space", spaceId).addValue("by", deletedBy)
                        .addValue("now", Timestamp.from(clock.instant()))) > 0;
    }

    /** The space's live handles a generated handle for {@code base} could collide with. */
    public Set<String> liveHandlesWithBase(UUID spaceId, String base) {
        String stem = base.substring(0, Math.min(base.length(), HANDLE_STEM));
        return new LinkedHashSet<>(jdbc.queryForList("SELECT handle FROM space_entity "
                + "WHERE space_id = :space AND deleted_at IS NULL AND handle LIKE :stem",
                new MapSqlParameterSource("space", spaceId).addValue("stem", SqlLike.prefix(stem)), String.class));
    }

    /** Whether a live entity of the space other than {@code entityId} holds the handle. */
    public boolean handleHeldByAnother(UUID spaceId, String handle, UUID entityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM space_entity WHERE space_id = :space "
                + "AND handle = :handle AND deleted_at IS NULL AND id <> :id)",
                new MapSqlParameterSource("space", spaceId).addValue("handle", handle).addValue("id", entityId),
                Boolean.class));
    }

    /** The label each of the text keys has among the space's live entities, the earliest use winning. */
    public Map<String, String> spaceTagLabels(UUID spaceId, Collection<String> keys) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return labels;
        }
        jdbc.query("SELECT t.tag_key, t.tag_label FROM space_entity_tag t JOIN space_entity e ON e.id = t.entity_id "
                + "WHERE e.space_id = :space AND e.deleted_at IS NULL AND t.tag_key IN (:keys) "
                + "ORDER BY t.created_at, t.entity_id",
                new MapSqlParameterSource("space", spaceId).addValue("keys", keys),
                rs -> {
                    labels.putIfAbsent(rs.getString("tag_key"), rs.getString("tag_label"));
                });
        return labels;
    }

    /** The space's live entities matching {@code filter}, newest update first, id breaking ties. */
    public List<EntityRow> page(UUID spaceId, EntityFilter filter, int page, int size) {
        Where where = where(spaceId, filter, true);
        return jdbc.query("SELECT " + COLUMNS + " FROM space_entity e WHERE " + where.sql()
                + " ORDER BY e.updated_at DESC, e.id LIMIT :limit OFFSET :offset",
                where.params().addValue("limit", size).addValue("offset", (long) page * size), ROWS);
    }

    /** Matching live entities per kind wire value; the filter's own kind is ignored. */
    public Map<String, Long> countByKind(UUID spaceId, EntityFilter filter) {
        Where where = where(spaceId, filter, false);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT e.kind, count(*) AS total FROM space_entity e WHERE " + where.sql() + " GROUP BY e.kind",
                where.params(), rs -> {
                    counts.put(rs.getString("kind"), rs.getLong("total"));
                });
        return counts;
    }

    public Map<UUID, List<PartRow>> partsOf(Collection<UUID> entityIds) {
        Map<UUID, List<PartRow>> parts = new HashMap<>();
        if (entityIds.isEmpty()) {
            return parts;
        }
        jdbc.query("SELECT id, entity_id, asset_id, role, label, sort, created_at FROM space_entity_part "
                + "WHERE entity_id IN (:ids) ORDER BY entity_id, sort",
                new MapSqlParameterSource("ids", entityIds),
                rs -> {
                    parts.computeIfAbsent(rs.getObject("entity_id", UUID.class), k -> new ArrayList<>())
                            .add(new PartRow(rs.getObject("id", UUID.class), rs.getObject("asset_id", UUID.class),
                                    rs.getString("role"), rs.getString("label"), rs.getInt("sort"),
                                    rs.getTimestamp("created_at").toInstant()));
                });
        return parts;
    }

    /** Tag labels of each entity, in text-key order. */
    public Map<UUID, List<String>> tagsOf(Collection<UUID> entityIds) {
        Map<UUID, List<String>> tags = new HashMap<>();
        if (entityIds.isEmpty()) {
            return tags;
        }
        jdbc.query("SELECT entity_id, tag_label FROM space_entity_tag WHERE entity_id IN (:ids) "
                + "ORDER BY entity_id, tag_key",
                new MapSqlParameterSource("ids", entityIds),
                rs -> {
                    tags.computeIfAbsent(rs.getObject("entity_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getString("tag_label"));
                });
        return tags;
    }

    private Optional<EntityRow> selectOne(String lock, UUID id, UUID spaceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM space_entity e WHERE e.id = :id AND e.space_id = :space "
                + "AND e.deleted_at IS NULL" + lock,
                new MapSqlParameterSource("id", id).addValue("space", spaceId), ROWS).stream().findFirst();
    }

    private record Where(String sql, MapSqlParameterSource params) {
    }

    private static Where where(UUID spaceId, EntityFilter filter, boolean withKind) {
        StringBuilder sql = new StringBuilder("e.space_id = :space AND e.deleted_at IS NULL");
        MapSqlParameterSource params = new MapSqlParameterSource("space", spaceId);
        if (withKind && filter.kind() != null) {
            sql.append(" AND e.kind = :kind");
            params.addValue("kind", filter.kind());
        }
        if (filter.qKey() != null) {
            sql.append(" AND (e.search_key LIKE :q OR e.handle LIKE :q)");
            params.addValue("q", SqlLike.contains(filter.qKey()));
        }
        if (filter.tagKey() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM space_entity_tag t WHERE t.entity_id = e.id AND t.tag_key = :tag)");
            params.addValue("tag", filter.tagKey());
        }
        if (filter.categoryKey() != null) {
            sql.append(" AND e.category_key = :category");
            params.addValue("category", filter.categoryKey());
        }
        return new Where(sql.toString(), params);
    }

    private static List<Object> partKey(UUID assetId, String role, String label) {
        return Arrays.asList(assetId, role, label);
    }

    private static String categoryKey(String categoryLabel) {
        return categoryLabel == null ? null : EntityText.key(categoryLabel);
    }

    private static Map<String, Object> snapshotJson(EntitySnapshot snapshot) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", snapshot.name());
        json.put("handle", snapshot.handle());
        json.put("fields", snapshot.fields());
        json.put("categoryLabel", snapshot.categoryLabel());
        json.put("tags", snapshot.tags());
        json.put("parts", snapshot.parts().stream().map(part -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assetId", part.assetId().toString());
            entry.put("role", part.role());
            entry.put("label", part.label());
            entry.put("sort", part.sort());
            return entry;
        }).toList());
        return json;
    }

    private static List<Map<String, Object>> changesJson(List<Change> changes) {
        return changes.stream().map(change -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", change.code());
            if (!change.added().isEmpty()) {
                entry.put("added", change.added());
            }
            if (!change.removed().isEmpty()) {
                entry.put("removed", change.removed());
            }
            if (change.from() != null) {
                entry.put("from", change.from());
            }
            return entry;
        }).toList();
    }

    private static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }
}
