package com.flowcortex.chat.folder;

import com.flowcortex.chat.conversation.ConversationStore.StoredConversation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's conversation folders. Every statement is scoped by the owner, so a folder of someone else looks the
 * same as one that does not exist.
 */
@Component("chatFolderStore")
public class FolderStore {

    private static final String CONVERSATION_COLUMNS =
            "c.id, c.title, c.last_activity_at, c.created_at, c.pinned, c.folder_id";

    /** The folder with the number of the owner's conversations it holds, pinned ones included. */
    private static final String FOLDER = "SELECT f.id, f.name, f.created_at, count(c.id) AS conversation_count "
            + "FROM chat_folder f LEFT JOIN chat_conversation c ON c.folder_id = f.id AND c.owner_id = f.owner_id";

    private static final RowMapper<StoredFolder> FOLDER_ROW = (rs, rowNum) -> new StoredFolder(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("conversation_count"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<StoredConversation> CONVERSATION_ROW = (rs, rowNum) -> new StoredConversation(
            rs.getObject("id", UUID.class), rs.getString("title"),
            rs.getTimestamp("last_activity_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("pinned"), rs.getObject("folder_id", UUID.class));

    private final JdbcTemplate jdbcTemplate;

    FolderStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record StoredFolder(UUID id, String name, int conversationCount, Instant createdAt) {
    }

    /** What a move found missing: nothing (it moved), the conversation, or the target folder. */
    public enum Missing { NONE, CONVERSATION, FOLDER }

    /** The moved conversation, or which side was not the owner's. */
    public record MoveResult(StoredConversation conversation, Missing missing) {
    }

    /** The owner's folders in creation order. */
    @Transactional(readOnly = true)
    public List<StoredFolder> list(UUID ownerId) {
        return jdbcTemplate.query(FOLDER + " WHERE f.owner_id = ? GROUP BY f.id ORDER BY f.created_at, f.id", FOLDER_ROW,
                ownerId);
    }

    /**
     * Creates a folder unless the owner already holds {@code cap}. The count and the insert run under one advisory
     * lock per owner, so two concurrent creates at {@code cap - 1} cannot both pass; empty means the cap is reached.
     */
    @Transactional
    public Optional<StoredFolder> create(UUID ownerId, FolderName name, int cap) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended('chat_folder:' || ?, 0))",
                rs -> { }, ownerId.toString());
        int held = jdbcTemplate.queryForObject("SELECT count(*) FROM chat_folder WHERE owner_id = ?", Integer.class,
                ownerId);
        if (held >= cap) {
            return Optional.empty();
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_folder (id, owner_id, name) VALUES (?, ?, ?)", id, ownerId,
                name.value());
        return find(ownerId, id);
    }

    /** Renames the owner's folder; empty when it is not theirs. */
    @Transactional
    public Optional<StoredFolder> rename(UUID ownerId, UUID id, FolderName name) {
        int updated = jdbcTemplate.update("UPDATE chat_folder SET name = ?, updated_at = now() "
                + "WHERE id = ? AND owner_id = ?", name.value(), id, ownerId);
        if (updated != 1) {
            return Optional.empty();
        }
        return find(ownerId, id);
    }

    /**
     * Deletes the owner's folder; its conversations stay, unfiled and still pinned if they were. The unlink is
     * explicit first, and the foreign key's {@code ON DELETE SET NULL} covers a move that commits meanwhile.
     */
    @Transactional
    public boolean delete(UUID ownerId, UUID id) {
        jdbcTemplate.update("UPDATE chat_conversation SET folder_id = NULL WHERE folder_id = ? AND owner_id = ?",
                id, ownerId);
        return jdbcTemplate.update("DELETE FROM chat_folder WHERE id = ? AND owner_id = ?", id, ownerId) == 1;
    }

    /**
     * Files the owner's conversation into the owner's folder, or back to Recent when {@code folderId} is null,
     * without touching its activity time. The update joins the folder row, so it proves both owners in one
     * statement; when it changes nothing, a read tells a missing conversation from a missing folder.
     */
    @Transactional
    public MoveResult move(UUID ownerId, UUID conversationId, UUID folderId) {
        List<StoredConversation> moved = folderId == null
                ? jdbcTemplate.query("UPDATE chat_conversation c SET folder_id = NULL WHERE c.id = ? "
                        + "AND c.owner_id = ? RETURNING " + CONVERSATION_COLUMNS, CONVERSATION_ROW, conversationId,
                        ownerId)
                : jdbcTemplate.query("UPDATE chat_conversation c SET folder_id = f.id FROM chat_folder f "
                        + "WHERE c.id = ? AND c.owner_id = ? AND f.id = ? AND f.owner_id = ? RETURNING "
                        + CONVERSATION_COLUMNS, CONVERSATION_ROW, conversationId, ownerId, folderId, ownerId);
        if (!moved.isEmpty()) {
            return new MoveResult(moved.getFirst(), Missing.NONE);
        }
        boolean conversationOwned = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = ? AND owner_id = ?)", Boolean.class,
                conversationId, ownerId));
        return new MoveResult(null, conversationOwned && folderId != null ? Missing.FOLDER : Missing.CONVERSATION);
    }

    private Optional<StoredFolder> find(UUID ownerId, UUID id) {
        return jdbcTemplate.query(FOLDER + " WHERE f.id = ? AND f.owner_id = ? GROUP BY f.id", FOLDER_ROW, id, ownerId).stream()
                .findFirst();
    }
}
