package com.flowcortex.chat.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat conversations and their messages, each conversation owned by exactly one member. Every
 * statement is scoped by the owner, so a conversation of someone else looks the same as one that
 * does not exist.
 */
@Component("chatConversationStore")
public class ConversationStore {

    private static final RowMapper<StoredConversation> CONVERSATION = (rs, rowNum) -> new StoredConversation(
            rs.getObject("id", UUID.class), rs.getString("title"),
            rs.getTimestamp("last_activity_at").toInstant(), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<StoredMessage> MESSAGE = (rs, rowNum) -> new StoredMessage(
            rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("role"), rs.getString("kind"),
            rs.getString("body"), rs.getString("payload"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    ConversationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record StoredConversation(UUID id, String title, Instant lastActivityAt, Instant createdAt) {
    }

    public record StoredMessage(UUID id, int seq, String role, String kind, String body, String payload,
            String status, Instant createdAt) {
    }

    /** Newest activity first; equal activity falls back to the larger id first. */
    @Transactional(readOnly = true)
    public List<StoredConversation> list(UUID ownerId) {
        return jdbcTemplate.query("SELECT id, title, last_activity_at, created_at FROM chat_conversation "
                + "WHERE owner_id = ? ORDER BY last_activity_at DESC, id DESC", CONVERSATION, ownerId);
    }

    @Transactional
    public StoredConversation create(UUID ownerId, String title) {
        return create(ownerId, UUID.randomUUID(), title);
    }

    /** Creates the conversation under a caller-chosen id, so a claim written first can name it. */
    @Transactional
    public StoredConversation create(UUID ownerId, UUID id, String title) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO chat_conversation (id, owner_id, title, created_at, updated_at, "
                + "last_activity_at) VALUES (?, ?, ?, ?, ?, ?)", id, ownerId, title, now, now, now);
        return new StoredConversation(id, title, now.toInstant(), now.toInstant());
    }

    /** Renames the owner's conversation without touching its activity time; empty when it is not theirs. */
    @Transactional
    public Optional<StoredConversation> rename(UUID ownerId, UUID id, String title) {
        int updated = jdbcTemplate.update("UPDATE chat_conversation SET title = ?, updated_at = now() "
                + "WHERE id = ? AND owner_id = ?", title, id, ownerId);
        if (updated != 1) {
            return Optional.empty();
        }
        return jdbcTemplate.query("SELECT id, title, last_activity_at, created_at FROM chat_conversation "
                + "WHERE id = ? AND owner_id = ?", CONVERSATION, id, ownerId).stream().findFirst();
    }

    /** Deletes the owner's conversation; its messages go with it by cascade, the runs it started stay. */
    @Transactional
    public boolean delete(UUID ownerId, UUID id) {
        return jdbcTemplate.update("DELETE FROM chat_conversation WHERE id = ? AND owner_id = ?", id, ownerId) == 1;
    }

    /** The owner's messages by seq; empty when the conversation is not theirs or does not exist. */
    @Transactional(readOnly = true)
    public Optional<List<StoredMessage>> messages(UUID ownerId, UUID id) {
        boolean owned = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = ? AND owner_id = ?)",
                Boolean.class, id, ownerId));
        if (!owned) {
            return Optional.empty();
        }
        return Optional.of(jdbcTemplate.query("SELECT id, seq, role, kind, body, payload::text AS payload, status, "
                + "created_at FROM chat_message WHERE conversation_id = ? ORDER BY seq", MESSAGE, id));
    }

    @Transactional(readOnly = true)
    public Optional<StoredConversation> find(UUID ownerId, UUID id) {
        return jdbcTemplate.query("SELECT id, title, last_activity_at, created_at FROM chat_conversation "
                + "WHERE id = ? AND owner_id = ?", CONVERSATION, id, ownerId).stream().findFirst();
    }
}
