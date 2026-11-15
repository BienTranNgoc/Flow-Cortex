package com.flowcortex.chat.turn;

import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.web.ChatProblemException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Every statement a turn runs: the send transaction's rows, the runner's checkpoints and conditional terminal
 * writes, the sweeper's scan. Nothing here opens a transaction; the callers decide which statements share one.
 */
@Component("chatTurnStore")
class TurnStore {

    private static final String STREAMING_INDEX = "uq_chat_message_streaming";

    private static final RowMapper<StoredMessage> MESSAGE = (rs, rowNum) -> new StoredMessage(
            rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("role"), rs.getString("kind"),
            rs.getString("body"), rs.getString("payload"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    TurnStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The stored claim of an {@code Idempotency-Key}. */
    record Claim(String requestHash, UUID conversationId, UUID assistantId) {
    }

    /**
     * A message of the prompt window, oldest first; {@code attachmentNames} is null without attached images.
     * {@code cardName} and {@code cardState} describe a card, {@code runId} a card's run or a run message.
     */
    record Turn(String role, String kind, String status, String body, String attachmentNames, String cardName,
            String cardState, String runId) {
    }

    /** What the answer row of a turn needs to be retried. */
    record AnswerRow(int seq, String role, String kind, String status) {
    }

    /** A turn the sweeper found past its deadline. */
    record Stuck(UUID messageId, UUID conversationId, UUID ownerId, LocalDate chargeDay) {
    }

    /** Claims the key for this send; false when an earlier or concurrent send already holds it. */
    boolean claimKey(UUID ownerId, String key, String requestHash, UUID conversationId, UUID assistantId,
            Instant expiredBefore) {
        jdbcTemplate.update("DELETE FROM chat_idempotency WHERE owner_id = ? AND key = ? AND created_at < ?",
                ownerId, key, Timestamp.from(expiredBefore));
        return jdbcTemplate.update("INSERT INTO chat_idempotency (owner_id, key, request_hash, conversation_id, "
                + "message_id) VALUES (?, ?, ?, ?, ?) ON CONFLICT (owner_id, key) DO NOTHING",
                ownerId, key, requestHash, conversationId, assistantId) == 1;
    }

    Optional<Claim> claim(UUID ownerId, String key) {
        return jdbcTemplate.query("SELECT request_hash, conversation_id, message_id FROM chat_idempotency "
                + "WHERE owner_id = ? AND key = ?", (rs, n) -> new Claim(rs.getString("request_hash"),
                rs.getObject("conversation_id", UUID.class), rs.getObject("message_id", UUID.class)),
                ownerId, key).stream().findFirst();
    }

    boolean conversationOwned(UUID ownerId, UUID conversationId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = ? AND owner_id = ?)", Boolean.class,
                conversationId, ownerId));
    }

    /**
     * Reserves {@code count} message numbers and stamps the conversation active.
     * @return the first number reserved; empty when the conversation is not the owner's
     */
    Optional<Integer> allocateSeq(UUID ownerId, UUID conversationId, int count) {
        return jdbcTemplate.queryForList("UPDATE chat_conversation SET next_seq = next_seq + ?, "
                + "last_activity_at = now() WHERE id = ? AND owner_id = ? RETURNING next_seq - ?", Integer.class,
                count, conversationId, ownerId, count).stream().findFirst();
    }

    boolean hasUserMessage(UUID conversationId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_message WHERE conversation_id = ? AND role = 'user')",
                Boolean.class, conversationId));
    }

    void insertUserMessage(UUID id, UUID conversationId, int seq, String text, String payloadJson,
            String requestHash) {
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, payload, status, "
                + "request_hash) VALUES (?, ?, ?, 'user', 'text', ?, ?::jsonb, 'done', ?)", id, conversationId, seq,
                text, payloadJson, requestHash);
    }

    /** @throws ChatProblemException 409 {@code chat-turn-in-progress} when the conversation already streams */
    void insertAssistant(UUID id, UUID conversationId, int seq, Instant deadline, String requestHash) {
        try {
            jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, status, "
                    + "turn_deadline, request_hash) VALUES (?, ?, ?, 'assistant', 'text', '', 'streaming', ?, ?)",
                    id, conversationId, seq, Timestamp.from(deadline), requestHash);
        } catch (DuplicateKeyException e) {
            if (e.getMostSpecificCause().getMessage().contains(STREAMING_INDEX)) {
                throw ChatProblemException.turnInProgress();
            }
            throw e;
        }
    }

    void setChargeDay(UUID messageId, LocalDate day) {
        jdbcTemplate.update("UPDATE chat_message SET charge_day = ? WHERE id = ?", day, messageId);
    }

    /** Retitles a conversation that still has one of its default titles. */
    void retitleDefault(UUID conversationId, String title, String... defaultTitles) {
        jdbcTemplate.update("UPDATE chat_conversation SET title = ?, updated_at = now() WHERE id = ? "
                + "AND title IN (?, ?)", title, conversationId, defaultTitles[0], defaultTitles[1]);
    }

    Optional<StoredMessage> message(UUID id) {
        return jdbcTemplate.query("SELECT id, seq, role, kind, body, payload::text AS payload, status, created_at "
                + "FROM chat_message WHERE id = ?", MESSAGE, id).stream().findFirst();
    }

    /** The newest member message before {@code seq}: the one an answer at {@code seq} replies to. */
    Optional<StoredMessage> userMessageBefore(UUID conversationId, int seq) {
        return jdbcTemplate.query("SELECT id, seq, role, kind, body, payload::text AS payload, status, created_at "
                + "FROM chat_message WHERE conversation_id = ? AND role = 'user' AND seq < ? "
                + "ORDER BY seq DESC LIMIT 1", MESSAGE, conversationId, seq).stream().findFirst();
    }

    Optional<AnswerRow> answerRow(UUID conversationId, UUID messageId) {
        return jdbcTemplate.query("SELECT seq, role, kind, status FROM chat_message WHERE id = ? "
                + "AND conversation_id = ?", (rs, n) -> new AnswerRow(rs.getInt("seq"), rs.getString("role"),
                rs.getString("kind"), rs.getString("status")), messageId, conversationId).stream().findFirst();
    }

    /** Whether the conversation holds any message after {@code seq}. */
    boolean hasMessageAfter(UUID conversationId, int seq) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM chat_message "
                + "WHERE conversation_id = ? AND seq > ?)", Boolean.class, conversationId, seq));
    }

    /** Sets {@code stop_requested} on a still-streaming message; the number of rows that took it. */
    int requestStop(UUID conversationId, UUID messageId) {
        return jdbcTemplate.update("UPDATE chat_message SET stop_requested = true WHERE id = ? "
                + "AND conversation_id = ? AND status = 'streaming'", messageId, conversationId);
    }

    /** Empty when the message is gone or no longer streaming; else whether a stop was requested. */
    Optional<Boolean> stopRequested(UUID messageId) {
        return jdbcTemplate.queryForList("SELECT stop_requested FROM chat_message WHERE id = ? "
                + "AND status = 'streaming'", Boolean.class, messageId).stream().findFirst();
    }

    /** The last {@code limit} messages up to {@code untilSeq}, oldest first. */
    List<Turn> window(UUID conversationId, int untilSeq, int limit) {
        List<Turn> newestFirst = jdbcTemplate.query("SELECT role, kind, status, body, "
                + "(SELECT string_agg(a->>'fileName', ', ') FROM jsonb_array_elements("
                + "CASE WHEN jsonb_typeof(payload->'attachments') = 'array' THEN payload->'attachments' "
                + "ELSE '[]'::jsonb END) a) AS attachment_names, payload->>'workflowName' AS card_name, "
                + "payload->>'state' AS card_state, payload->>'runId' AS run_id FROM chat_message "
                + "WHERE conversation_id = ? AND seq <= ? ORDER BY seq DESC LIMIT ?",
                (rs, n) -> new Turn(rs.getString("role"), rs.getString("kind"), rs.getString("status"),
                        rs.getString("body"), rs.getString("attachment_names"), rs.getString("card_name"),
                        rs.getString("card_state"), rs.getString("run_id")), conversationId, untilSeq, limit);
        return newestFirst.reversed();
    }

    /** The payload of the message at {@code seq}, or null. */
    String payloadAt(UUID conversationId, int seq) {
        return jdbcTemplate.queryForList("SELECT payload::text FROM chat_message WHERE conversation_id = ? "
                + "AND seq = ?", String.class, conversationId, seq).stream().filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    int checkpoint(UUID messageId, String body) {
        return jdbcTemplate.update("UPDATE chat_message SET body = ? WHERE id = ? AND status = 'streaming'",
                body, messageId);
    }

    /**
     * The conditional terminal write: only the writer that moves the row out of {@code streaming} gets 1.
     * A null {@code body} or {@code payloadJson} leaves that column as it is.
     */
    int finish(UUID messageId, String status, String body, String payloadJson) {
        return jdbcTemplate.update("UPDATE chat_message SET status = ?, body = COALESCE(?, body), "
                + "payload = COALESCE(?::jsonb, payload) WHERE id = ? AND status = 'streaming'",
                status, body, payloadJson, messageId);
    }

    List<Stuck> stuck(Instant deadlineBefore) {
        return jdbcTemplate.query("SELECT m.id, m.conversation_id, c.owner_id, m.charge_day FROM chat_message m "
                + "JOIN chat_conversation c ON c.id = m.conversation_id WHERE m.status = 'streaming' "
                + "AND m.turn_deadline < ? ORDER BY m.turn_deadline", (rs, n) -> {
                    java.sql.Date day = rs.getDate("charge_day");
                    return new Stuck(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                            rs.getObject("owner_id", UUID.class), day == null ? null : day.toLocalDate());
                }, Timestamp.from(deadlineBefore));
    }

    int purgeIdempotency(Instant olderThan) {
        return jdbcTemplate.update("DELETE FROM chat_idempotency WHERE created_at < ?", Timestamp.from(olderThan));
    }
}
