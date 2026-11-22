package com.flowcortex.notification.infrastructure;

import com.flowcortex.notification.api.NotificationRequested;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notifications and the in-app preference. Every read and write is filtered by the owner, so an id
 * of another member is indistinguishable from an unknown one. Writes that must not repeat are
 * conditional and report the rows they changed.
 */
@Repository
public class NotificationRepository {

    private static final TypeReference<List<Object>> ARGS = new TypeReference<>() { };
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RowMapper<NotificationRow> rowMapper = this::row;

    NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored notification as the read route renders it. */
    public record NotificationRow(UUID id, String type, String titleCode, String bodyCode, List<Object> args,
            String link, Instant readAt, Instant createdAt) {
    }

    /** One page of rows; {@code hasMore} says a further page exists. */
    public record RowPage(List<NotificationRow> rows, boolean hasMore) {
    }

    /** @return 1 when stored, 0 when a notification with that id already exists */
    public int insert(NotificationRequested request, Instant now) {
        return jdbc.update("INSERT INTO notification (id, user_id, type, title_code, body_code, args, link, "
                + "created_at) VALUES (:id, :user, :type, :titleCode, :bodyCode, CAST(:args AS jsonb), :link, :now) "
                + "ON CONFLICT (id) DO NOTHING", new MapSqlParameterSource()
                .addValue("id", request.id()).addValue("user", request.memberId())
                .addValue("type", request.type()).addValue("titleCode", request.titleCode())
                .addValue("bodyCode", request.bodyCode())
                .addValue("args", jsonMapper.writeValueAsString(request.args()))
                .addValue("link", request.link()).addValue("now", Timestamp.from(now)));
    }

    /** Deletes the member's notifications beyond the newest {@code cap}. @return rows deleted */
    public int trim(UUID userId, int cap) {
        return jdbc.update("DELETE FROM notification WHERE user_id = :user AND id IN (SELECT id FROM notification "
                + "WHERE user_id = :user ORDER BY created_at DESC, id DESC OFFSET :cap)",
                new MapSqlParameterSource().addValue("user", userId).addValue("cap", cap));
    }

    public RowPage page(UUID userId, boolean unreadOnly, int offset, int limit) {
        List<NotificationRow> rows = jdbc.query("SELECT id, type, title_code, body_code, args, link, read_at, "
                + "created_at FROM notification WHERE user_id = :user"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit",
                new MapSqlParameterSource().addValue("user", userId).addValue("offset", offset)
                        .addValue("limit", limit + 1), rowMapper);
        boolean hasMore = rows.size() > limit;
        return new RowPage(hasMore ? rows.subList(0, limit) : rows, hasMore);
    }

    public long unreadCount(UUID userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = :user AND read_at IS NULL",
                new MapSqlParameterSource("user", userId), Long.class);
        return count == null ? 0 : count;
    }

    /** @return 1 when the notification was unread and is now read, else 0 */
    public int markRead(UUID id, UUID userId, Instant now) {
        return jdbc.update("UPDATE notification SET read_at = :now WHERE id = :id AND user_id = :user "
                + "AND read_at IS NULL", new MapSqlParameterSource().addValue("id", id).addValue("user", userId)
                .addValue("now", Timestamp.from(now)));
    }

    public boolean exists(UUID id, UUID userId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM notification WHERE id = :id AND user_id = :user",
                new MapSqlParameterSource().addValue("id", id).addValue("user", userId), Integer.class);
        return found != null && found > 0;
    }

    /** @return the number of notifications that were unread */
    public int markAllRead(UUID userId, Instant now) {
        return jdbc.update("UPDATE notification SET read_at = :now WHERE user_id = :user AND read_at IS NULL",
                new MapSqlParameterSource().addValue("user", userId).addValue("now", Timestamp.from(now)));
    }

    /** No preference row means every in-app notification is on. */
    public boolean inAppEnabled(UUID userId) {
        List<Boolean> found = jdbc.query("SELECT in_app FROM notification_preference WHERE user_id = :user",
                new MapSqlParameterSource("user", userId), (rs, n) -> rs.getBoolean("in_app"));
        return found.isEmpty() || found.get(0);
    }

    private NotificationRow row(ResultSet rs, int rowNumber) throws SQLException {
        return new NotificationRow(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getString("title_code"), rs.getString("body_code"),
                jsonMapper.readValue(rs.getString("args"), ARGS), rs.getString("link"),
                instant(rs, "read_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
