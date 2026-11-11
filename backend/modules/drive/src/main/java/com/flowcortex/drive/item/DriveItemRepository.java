package com.flowcortex.drive.item;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code drive_item} rows; every read and write is scoped to one space, so another space's id is simply absent. */
@Repository
class DriveItemRepository {

    private static final String COLUMNS = "id, space_id, owner_id, name, content_type, semantic_role, text_value, "
            + "source, bucket, object_key, file_name, mime_type, size_bytes, run_id, step_order, created_at, updated_at";

    private static final String FILTER = "space_id = :space AND (CAST(:role AS varchar) IS NULL "
            + "OR semantic_role = :role) AND (CAST(:type AS varchar) IS NULL OR content_type = :type)";

    private static final RowMapper<DriveItemRow> ROWS = (rs, i) -> new DriveItemRow(
            rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class), rs.getObject("owner_id", UUID.class),
            rs.getString("name"), rs.getString("content_type"), rs.getString("semantic_role"),
            rs.getString("text_value"), rs.getString("source"), rs.getString("bucket"), rs.getString("object_key"),
            rs.getString("file_name"), rs.getString("mime_type"), rs.getObject("size_bytes", Long.class),
            rs.getObject("run_id", UUID.class), rs.getObject("step_order", Integer.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    /** What a space's uploaded files already hold. */
    record UploadUsage(long items, long bytes) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    DriveItemRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(DriveItemRow row) {
        jdbc.update("INSERT INTO drive_item (" + COLUMNS + ") VALUES (:id, :space, :owner, :name, :type, :role, :text, "
                + ":source, :bucket, :key, :file, :mime, :bytes, :run, :step, :created, :updated)",
                new MapSqlParameterSource("id", row.id())
                        .addValue("space", row.spaceId())
                        .addValue("owner", row.ownerId())
                        .addValue("name", row.name())
                        .addValue("type", row.contentType())
                        .addValue("role", row.semanticRole())
                        .addValue("text", row.textValue())
                        .addValue("source", row.source())
                        .addValue("bucket", row.bucket())
                        .addValue("key", row.objectKey())
                        .addValue("file", row.fileName())
                        .addValue("mime", row.mimeType())
                        .addValue("bytes", row.sizeBytes())
                        .addValue("run", row.runId())
                        .addValue("step", row.stepOrder())
                        .addValue("created", Timestamp.from(row.createdAt()))
                        .addValue("updated", Timestamp.from(row.updatedAt())));
    }

    /** Count and bytes of the space's uploaded files; run outputs point at run objects and add no storage. */
    UploadUsage uploadUsage(UUID spaceId) {
        return jdbc.queryForObject("SELECT count(*) AS items, COALESCE(sum(size_bytes), 0) AS bytes FROM drive_item "
                + "WHERE space_id = :space AND source = :source",
                new MapSqlParameterSource("space", spaceId).addValue("source", DriveItemRow.SOURCE_UPLOAD),
                (rs, i) -> new UploadUsage(rs.getLong("items"), rs.getLong("bytes")));
    }

    /**
     * Inserts an uploaded file's row unless the space would pass a cap. The check and the insert run under a
     * per-space advisory lock, so parallel creates cannot all pass the check; returns whether it inserted.
     */
    @Transactional
    boolean insertUploadWithinQuota(DriveItemRow row, long maxItems, long maxBytes) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(:space))",
                new MapSqlParameterSource("space", row.spaceId().toString()));
        UploadUsage usage = uploadUsage(row.spaceId());
        if (usage.items() + 1 > maxItems || usage.bytes() + row.sizeBytes() > maxBytes) {
            return false;
        }
        insert(row);
        return true;
    }

    /** Newest first; a {@code null} {@code role} or {@code contentType} does not filter. */
    List<DriveItemRow> page(UUID spaceId, String role, String contentType, int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM drive_item WHERE " + FILTER
                + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset",
                filter(spaceId, role, contentType).addValue("size", size).addValue("offset", (long) page * size), ROWS);
    }

    long count(UUID spaceId, String role, String contentType) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM drive_item WHERE " + FILTER,
                filter(spaceId, role, contentType), Long.class);
        return total == null ? 0 : total;
    }

    Optional<DriveItemRow> find(UUID spaceId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM drive_item WHERE space_id = :space AND id = :id",
                new MapSqlParameterSource("space", spaceId).addValue("id", id), ROWS).stream().findFirst();
    }

    /** Changes only the non-null arguments and stamps {@code updated_at}; returns the rows it matched. */
    int update(UUID spaceId, UUID id, String name, String role, String text, Instant now) {
        return jdbc.update("UPDATE drive_item SET name = COALESCE(:name, name), "
                + "semantic_role = COALESCE(:role, semantic_role), text_value = COALESCE(:text, text_value), "
                + "updated_at = :now WHERE space_id = :space AND id = :id",
                new MapSqlParameterSource("space", spaceId).addValue("id", id).addValue("name", name)
                        .addValue("role", role).addValue("text", text).addValue("now", Timestamp.from(now)));
    }

    int delete(UUID spaceId, UUID id) {
        return jdbc.update("DELETE FROM drive_item WHERE space_id = :space AND id = :id",
                new MapSqlParameterSource("space", spaceId).addValue("id", id));
    }

    private static MapSqlParameterSource filter(UUID spaceId, String role, String contentType) {
        return new MapSqlParameterSource("space", spaceId).addValue("role", role).addValue("type", contentType);
    }
}
