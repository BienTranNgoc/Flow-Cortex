package com.flowcortex.support.request;

import com.flowcortex.media.api.StoredAttachment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Support requests, their messages and attachments. The number is an identity column, so it is read back
 * from the insert. Every status change is conditional on the status it was decided from.
 */
@Repository
class RequestRepository {

    private static final String REQUEST_COLUMNS = "id, number, requester_id, type, subject, status, "
            + "ai_conversation_id, ai_conversation_linked, member_unread, last_activity_at, resolved_at, "
            + "resolved_seen_at, closed_reason, created_at";
    private static final RowMapper<RequestRow> REQUEST = (rs, n) -> new RequestRow(
            rs.getObject("id", UUID.class), rs.getLong("number"), rs.getObject("requester_id", UUID.class),
            rs.getString("type"), rs.getString("subject"), RequestStatus.of(rs.getString("status")),
            rs.getObject("ai_conversation_id", UUID.class), rs.getBoolean("ai_conversation_linked"),
            rs.getBoolean("member_unread"),
            instant(rs, "last_activity_at"), instant(rs, "resolved_at"), instant(rs, "resolved_seen_at"),
            rs.getString("closed_reason"), instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    RequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param aiConversationLinked whether the request was filed with a conversation; it stays true once the
     *        retention job deletes that conversation and {@code aiConversationId} becomes null
     */
    record RequestRow(UUID id, long number, UUID requesterId, String type, String subject, RequestStatus status,
            UUID aiConversationId, boolean aiConversationLinked, boolean memberUnread, Instant lastActivityAt,
            Instant resolvedAt, Instant resolvedSeenAt, String closedReason, Instant createdAt) {
    }

    record MessageRow(UUID id, String authorKind, String body, Instant createdAt) {
    }

    record AttachmentRow(UUID messageId, int position, String bucket, String objectKey, String contentType,
            long sizeBytes) {
    }

    record ConversationMessageRow(String role, String text, String outcome, Instant createdAt) {
    }

    /** @return the number the database gave the request */
    long insertRequest(UUID id, UUID requesterId, String type, String subject, UUID aiConversationId, Instant now) {
        Long number = jdbc.queryForObject("INSERT INTO support_request (id, requester_id, type, subject, status, "
                + "ai_conversation_id, ai_conversation_linked, member_unread, last_activity_at, created_at, "
                + "updated_at) VALUES (:id, :requester, :type, :subject, 'new', :conversation, :linked, false, :now, "
                + ":now, :now) RETURNING number", new MapSqlParameterSource()
                .addValue("id", id).addValue("requester", requesterId).addValue("type", type)
                .addValue("subject", subject).addValue("conversation", aiConversationId)
                .addValue("linked", aiConversationId != null)
                .addValue("now", Timestamp.from(now)), Long.class);
        return number;
    }

    Optional<RequestRow> findByNumber(long number) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM support_request WHERE number = :number",
                new MapSqlParameterSource("number", number), REQUEST).stream().findFirst();
    }

    Optional<RequestRow> findById(UUID id) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM support_request WHERE id = :id",
                new MapSqlParameterSource("id", id), REQUEST).stream().findFirst();
    }

    Optional<UUID> findRequestIdOfMessage(UUID messageId) {
        return jdbc.queryForList("SELECT request_id FROM support_message WHERE id = :id",
                new MapSqlParameterSource("id", messageId), UUID.class).stream().findFirst();
    }

    List<RequestRow> listOfRequester(UUID requesterId) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM support_request WHERE requester_id = :requester "
                + "ORDER BY last_activity_at DESC, number DESC", new MapSqlParameterSource("requester", requesterId),
                REQUEST);
    }

    List<RequestRow> page(String status, String type, int offset, int limit) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM support_request" + filter(status, type)
                + " ORDER BY last_activity_at DESC, number DESC OFFSET :offset LIMIT :limit",
                filterParams(status, type).addValue("offset", offset).addValue("limit", limit), REQUEST);
    }

    long count(String status, String type) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM support_request" + filter(status, type),
                filterParams(status, type), Long.class);
        return count;
    }

    long countUnread(UUID requesterId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM support_request WHERE requester_id = :requester "
                + "AND member_unread", new MapSqlParameterSource("requester", requesterId), Long.class);
        return count;
    }

    long countOpen() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM support_request WHERE status IN ('new', 'in_progress')",
                new MapSqlParameterSource(), Long.class);
        return count;
    }

    /**
     * Moves the request from {@code from} to {@code to} only if it is still in {@code from}. Entering resolved
     * stamps the resolution and restarts the close clock; any other status clears both.
     * @param memberUnread the new unread mark, or null to keep it
     * @return whether this change won
     */
    boolean move(UUID id, RequestStatus from, RequestStatus to, String closedReason, Boolean memberUnread,
            Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("UPDATE support_request SET status = :to, closed_reason = :reason, "
                + "resolved_at = :resolvedAt, resolved_seen_at = NULL, "
                + "member_unread = COALESCE(:unread, member_unread), last_activity_at = :now, updated_at = :now "
                + "WHERE id = :id AND status = :from", new MapSqlParameterSource()
                .addValue("to", to.value()).addValue("reason", closedReason)
                .addValue("resolvedAt", to == RequestStatus.RESOLVED ? at : null, java.sql.Types.TIMESTAMP)
                .addValue("unread", memberUnread, java.sql.Types.BOOLEAN)
                .addValue("now", at).addValue("id", id).addValue("from", from.value())) == 1;
    }

    /**
     * Closes a resolution only while its member's first sight of it is still at or before {@code cutoff}: a
     * re-resolve after the job read the row clears that sight and so keeps the request open.
     * @return whether this close won
     */
    boolean closeSeenResolution(UUID id, String closedReason, Instant cutoff, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("UPDATE support_request SET status = 'closed', closed_reason = :reason, "
                + "resolved_at = NULL, resolved_seen_at = NULL, last_activity_at = :now, updated_at = :now "
                + "WHERE id = :id AND status = 'resolved' AND resolved_seen_at <= :cutoff", new MapSqlParameterSource()
                .addValue("reason", closedReason).addValue("now", at).addValue("id", id)
                .addValue("cutoff", Timestamp.from(cutoff))) == 1;
    }

    /** Clears the unread mark; the first read of a resolution starts its close clock. */
    void markSeen(UUID id, Instant now) {
        jdbc.update("UPDATE support_request SET member_unread = false, resolved_seen_at = CASE "
                + "WHEN status = 'resolved' AND resolved_seen_at IS NULL THEN :now ELSE resolved_seen_at END "
                + "WHERE id = :id", new MapSqlParameterSource("id", id).addValue("now", Timestamp.from(now)));
    }

    /** Resolutions whose member first saw them at or before {@code cutoff}. */
    List<RequestRow> seenResolutionsBefore(Instant cutoff) {
        return jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM support_request WHERE status = 'resolved' "
                + "AND resolved_seen_at <= :cutoff", new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)),
                REQUEST);
    }

    void insertMessage(UUID id, UUID requestId, String authorKind, UUID authorId, String body, Instant now) {
        jdbc.update("INSERT INTO support_message (id, request_id, author_kind, author_id, body, created_at) "
                + "VALUES (:id, :request, :kind, :author, :body, :now)", new MapSqlParameterSource()
                .addValue("id", id).addValue("request", requestId).addValue("kind", authorKind)
                .addValue("author", authorId).addValue("body", body).addValue("now", Timestamp.from(now)));
    }

    void insertAttachments(UUID messageId, List<StoredAttachment> attachments, Instant now) {
        for (int index = 0; index < attachments.size(); index++) {
            StoredAttachment attachment = attachments.get(index);
            jdbc.update("INSERT INTO support_attachment (id, message_id, bucket, object_key, content_type, "
                    + "size_bytes, position, created_at) VALUES (:id, :message, :bucket, :key, :type, :size, "
                    + ":position, :now)", new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID()).addValue("message", messageId)
                    .addValue("bucket", attachment.bucket()).addValue("key", attachment.objectKey())
                    .addValue("type", attachment.contentType()).addValue("size", attachment.sizeBytes())
                    .addValue("position", index + 1).addValue("now", Timestamp.from(now)));
        }
    }

    List<MessageRow> messages(UUID requestId) {
        return jdbc.query("SELECT id, author_kind, body, created_at FROM support_message WHERE request_id = :request "
                + "ORDER BY created_at, id", new MapSqlParameterSource("request", requestId),
                (rs, n) -> new MessageRow(rs.getObject("id", UUID.class), rs.getString("author_kind"),
                        rs.getString("body"), instant(rs, "created_at")));
    }

    List<AttachmentRow> attachments(Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT message_id, position, bucket, object_key, content_type, size_bytes "
                + "FROM support_attachment WHERE message_id IN (:messages) ORDER BY position",
                new MapSqlParameterSource("messages", messageIds),
                (rs, n) -> new AttachmentRow(rs.getObject("message_id", UUID.class), rs.getInt("position"),
                        rs.getString("bucket"), rs.getString("object_key"), rs.getString("content_type"),
                        rs.getLong("size_bytes")));
    }

    boolean ownsConversation(UUID conversationId, UUID ownerId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM support_ai_conversation WHERE id = :id "
                + "AND owner_user_id = :owner", new MapSqlParameterSource("id", conversationId)
                .addValue("owner", ownerId), Long.class);
        return count > 0;
    }

    List<ConversationMessageRow> conversation(UUID conversationId) {
        return jdbc.query("SELECT role, text, outcome, created_at FROM support_ai_message "
                + "WHERE conversation_id = :id ORDER BY created_at, id", new MapSqlParameterSource("id", conversationId),
                (rs, n) -> new ConversationMessageRow(rs.getString("role"), rs.getString("text"),
                        rs.getString("outcome"), instant(rs, "created_at")));
    }

    private static String filter(String status, String type) {
        List<String> clauses = new ArrayList<>();
        if (status != null) {
            clauses.add("status = :status");
        }
        if (type != null) {
            clauses.add("type = :type");
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static MapSqlParameterSource filterParams(String status, String type) {
        return new MapSqlParameterSource().addValue("status", status).addValue("type", type);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
