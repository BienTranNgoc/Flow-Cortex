package com.flowcortex.support.assistant;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assistant conversations, each owned by exactly one member or one hashed guest session. Every read
 * is scoped by the owner, so a conversation of someone else looks the same as one that does not exist.
 */
@Component
class ConversationStore {

    private final JdbcTemplate jdbcTemplate;

    ConversationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    record StoredMessage(String role, String text, String outcome, List<String> citedSlugs, Instant createdAt) {
    }

    record StoredConversation(UUID id, List<StoredMessage> messages) {
    }

    @Transactional(readOnly = true)
    boolean owns(Asker asker, UUID conversationId) {
        return !jdbcTemplate.queryForList("SELECT id FROM support_ai_conversation WHERE id = ? AND "
                + ownerColumn(asker) + " = ?", UUID.class, conversationId, owner(asker)).isEmpty();
    }

    /**
     * Appends the question and its answer to the asker's conversation, starting one when
     * {@code conversationId} is null, and returns the conversation id. A conversation that is gone or
     * not the asker's is 404 {@code conversation-not-found}.
     */
    @Transactional
    UUID append(Asker asker, UUID conversationId, StoredMessage question, StoredMessage answer) {
        UUID id = conversationId;
        Timestamp last = Timestamp.from(answer.createdAt());
        if (id == null) {
            id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO support_ai_conversation (id, " + ownerColumn(asker)
                    + ", last_message_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?)", id, owner(asker), last,
                    Timestamp.from(question.createdAt()), last);
        } else if (jdbcTemplate.update("UPDATE support_ai_conversation SET last_message_at = ?, updated_at = ? "
                + "WHERE id = ? AND " + ownerColumn(asker) + " = ?", last, last, id, owner(asker)) != 1) {
            throw AssistantService.conversationNotFound();
        }
        insert(id, question);
        insert(id, answer);
        return id;
    }

    /** The asker's conversation with the most recent message, its messages oldest first. */
    @Transactional(readOnly = true)
    Optional<StoredConversation> latest(Asker asker) {
        List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM support_ai_conversation WHERE "
                + ownerColumn(asker) + " = ? ORDER BY last_message_at DESC LIMIT 1", UUID.class, owner(asker));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        List<StoredMessage> messages = jdbcTemplate.query("SELECT role, text, outcome, cited_slugs, created_at "
                + "FROM support_ai_message WHERE conversation_id = ? "
                + "ORDER BY created_at, CASE role WHEN 'user' THEN 0 ELSE 1 END",
                (rs, rowNum) -> new StoredMessage(rs.getString("role"), rs.getString("text"), rs.getString("outcome"),
                        slugs(rs), rs.getTimestamp("created_at").toInstant()),
                ids.getFirst());
        return Optional.of(new StoredConversation(ids.getFirst(), messages));
    }

    /** Titles of the given slugs that are still published; an unpublished slug is absent. */
    @Transactional(readOnly = true)
    Map<String, String> publishedTitles(Set<String> slugs) {
        if (slugs.isEmpty()) {
            return Map.of();
        }
        List<String[]> rows = jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(
                    "SELECT slug, title FROM support_article WHERE status = 'published' AND slug = ANY (?)");
            statement.setArray(1, connection.createArrayOf("text", slugs.toArray()));
            return statement;
        }, (rs, rowNum) -> new String[] {rs.getString("slug"), rs.getString("title")});
        return rows.stream().collect(Collectors.toMap(row -> row[0], row -> row[1]));
    }

    private void insert(UUID conversationId, StoredMessage message) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO support_ai_message "
                    + "(id, conversation_id, role, text, outcome, cited_slugs, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)");
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, conversationId);
            statement.setString(3, message.role());
            statement.setString(4, message.text());
            statement.setString(5, message.outcome());
            statement.setArray(6, connection.createArrayOf("text", message.citedSlugs().toArray()));
            statement.setTimestamp(7, Timestamp.from(message.createdAt()));
            return statement;
        });
    }

    private static List<String> slugs(ResultSet rs) throws SQLException {
        Array array = rs.getArray("cited_slugs");
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private static String ownerColumn(Asker asker) {
        return asker instanceof Asker.Member ? "owner_user_id" : "guest_session_hash";
    }

    private static Object owner(Asker asker) {
        return switch (asker) {
            case Asker.Member member -> member.userId();
            case Asker.Guest guest -> guest.sessionHash();
        };
    }
}
