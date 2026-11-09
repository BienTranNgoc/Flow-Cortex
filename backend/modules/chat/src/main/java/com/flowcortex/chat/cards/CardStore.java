package com.flowcortex.chat.cards;

import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements behind cards and the run messages beside them. A card's {@code payload.state} moves
 * {@code pending} to {@code images-expired} and back, then once to {@code started}; every change is a
 * conditional update, so concurrent confirms cannot both be the one that starts it.
 */
@Component("chatCardStore")
public class CardStore {

    static final String PENDING = "pending";
    static final String IMAGES_EXPIRED = "images-expired";
    static final String STARTED = "started";

    private static final String COLUMNS = "id, seq, role, kind, body, payload::text AS payload, status, created_at";

    private static final RowMapper<StoredMessage> MESSAGE = (rs, rowNum) -> new StoredMessage(
            rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("role"), rs.getString("kind"),
            rs.getString("body"), rs.getString("payload"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    CardStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean conversationOwned(UUID ownerId, UUID conversationId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = ? AND owner_id = ?)", Boolean.class,
                conversationId, ownerId));
    }

    /** The card message of a conversation; empty when the id is not a card there. */
    public Optional<StoredMessage> card(UUID conversationId, UUID messageId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM chat_message WHERE id = ? AND conversation_id = ? "
                + "AND kind = 'card'", MESSAGE, messageId, conversationId).stream().findFirst();
    }

    /**
     * Appends a finished assistant message of {@code kind} ({@code card} or {@code run}) under the next free
     * number and stamps the conversation active.
     * @return the stored message; empty when the conversation is not the owner's any more
     */
    @Transactional
    public Optional<StoredMessage> append(UUID ownerId, UUID conversationId, String kind, String payloadJson) {
        Optional<Integer> seq = jdbcTemplate.queryForList("UPDATE chat_conversation SET next_seq = next_seq + 1, "
                + "last_activity_at = now() WHERE id = ? AND owner_id = ? RETURNING next_seq - 1", Integer.class,
                conversationId, ownerId).stream().findFirst();
        if (seq.isEmpty()) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, payload, status) "
                + "VALUES (?, ?, ?, 'assistant', ?, NULL, ?::jsonb, 'done')", id, conversationId, seq.get(), kind,
                payloadJson);
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM chat_message WHERE id = ?", MESSAGE, id)
                .stream().findFirst();
    }

    /** Merges {@code patchJson} into a card that has not started; the number of rows changed (0 or 1). */
    public int amendOpen(UUID messageId, String patchJson) {
        return jdbcTemplate.update("UPDATE chat_message SET payload = payload || ?::jsonb WHERE id = ? "
                + "AND payload->>'state' IN (?, ?)", patchJson, messageId, PENDING, IMAGES_EXPIRED);
    }

    /** Marks a pending card {@code images-expired}; 0 when it was not pending. */
    public int markImagesExpired(UUID messageId) {
        return jdbcTemplate.update("UPDATE chat_message SET payload = payload || "
                + "jsonb_build_object('state', ?::text) WHERE id = ? AND payload->>'state' = ?", IMAGES_EXPIRED,
                messageId, PENDING);
    }

    /** Marks an open card started with its run; 0 when another confirm already did. */
    public int markStarted(UUID messageId, UUID runId) {
        return jdbcTemplate.update("UPDATE chat_message SET payload = payload || jsonb_build_object('state', ?::text, "
                + "'runId', ?::text) WHERE id = ? AND payload->>'state' IN (?, ?)", STARTED, runId.toString(),
                messageId, PENDING, IMAGES_EXPIRED);
    }
}
