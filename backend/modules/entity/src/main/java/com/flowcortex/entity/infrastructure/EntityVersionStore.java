package com.flowcortex.entity.infrastructure;

import com.flowcortex.entity.domain.EntityChanges.Change;
import com.flowcortex.entity.domain.EntitySnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of {@code space_entity_version}. The rows are written by {@link EntityStore#appendVersion}; this store
 * does not scope to a space, so a caller checks the entity first.
 */
@Repository
public class EntityVersionStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Map<String, Object>>> CHANGES = new TypeReference<>() { };

    /** What {@link EntityStore#appendVersion} writes as the snapshot, read back. */
    record StoredSnapshot(String name, String handle, Map<String, Object> fields, String categoryLabel,
            List<String> tags, List<StoredPart> parts) {
    }

    record StoredPart(UUID assetId, String role, String label, int sort) {
    }

    /** A listed version: its changes and how many completed runs used it. */
    public record VersionSummary(int version, Instant createdAt, List<Change> changes, int usedInRuns) {
    }

    /** A version with the content it stored. */
    public record VersionRow(int version, Instant createdAt, EntitySnapshot snapshot) {
    }

    private static final RowMapper<VersionSummary> SUMMARIES = (rs, i) -> new VersionSummary(rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(), changesOf(rs.getString("changes")), rs.getInt("used_in_runs"));

    private final NamedParameterJdbcTemplate jdbc;

    public EntityVersionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of the entity's versions, newest first; {@code usedInRuns} counts the runs that completed on each. */
    public List<VersionSummary> page(UUID entityId, int page, int size) {
        return jdbc.query("SELECT v.version, v.created_at, v.changes::text AS changes, "
                + "(SELECT count(*) FROM space_entity_run r WHERE r.entity_id = v.entity_id "
                + "AND r.version = v.version AND r.completed_at IS NOT NULL) AS used_in_runs "
                + "FROM space_entity_version v WHERE v.entity_id = :entity ORDER BY v.version DESC "
                + "LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource("entity", entityId).addValue("limit", size)
                        .addValue("offset", (long) page * size),
                SUMMARIES);
    }

    public long count(UUID entityId) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = :entity",
                new MapSqlParameterSource("entity", entityId), Long.class);
        return total == null ? 0 : total;
    }

    public Optional<VersionRow> find(UUID entityId, int version) {
        return jdbc.query("SELECT version, created_at, snapshot::text AS snapshot FROM space_entity_version "
                + "WHERE entity_id = :entity AND version = :version",
                new MapSqlParameterSource("entity", entityId).addValue("version", version),
                (rs, i) -> new VersionRow(rs.getInt("version"), rs.getTimestamp("created_at").toInstant(),
                        snapshotOf(rs.getString("snapshot")))).stream().findFirst();
    }

    private static EntitySnapshot snapshotOf(String json) {
        StoredSnapshot stored = JSON.readValue(json, StoredSnapshot.class);
        List<EntitySnapshot.Part> parts = stored.parts() == null ? List.of() : stored.parts().stream()
                .map(part -> new EntitySnapshot.Part(part.assetId(), part.role(), part.label(), part.sort()))
                .toList();
        return new EntitySnapshot(stored.name(), stored.handle(), stored.fields(), stored.categoryLabel(),
                stored.tags(), parts);
    }

    private static List<Change> changesOf(String json) {
        List<Change> changes = new ArrayList<>();
        for (Map<String, Object> entry : JSON.readValue(json, CHANGES)) {
            Object from = entry.get("from");
            changes.add(new Change((String) entry.get("code"), labels(entry.get("added")), labels(entry.get("removed")),
                    from instanceof Number number ? number.intValue() : null));
        }
        return changes;
    }

    private static List<String> labels(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
