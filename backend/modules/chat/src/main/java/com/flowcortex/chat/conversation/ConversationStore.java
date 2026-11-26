package com.flowcortex.chat.conversation;

import com.flowcortex.chat.conversation.ConversationQuery.FolderFilter;
import com.flowcortex.chat.conversation.ConversationQuery.Pinned;
import com.flowcortex.chat.conversation.ListCursor.Sort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

    /** Length of a row's preview; the member app mirrors it to cut a preview on a whole word. */
    public static final int PREVIEW_CHARS = 200;

    private static final String COLUMNS = "id, title, last_activity_at, created_at, pinned, folder_id";

    /** The latest text message with a non-blank body, trimmed; no row when there is none. */
    private static final String LATEST_TEXT = "LEFT JOIN LATERAL (SELECT btrim(m.body, E' \\t\\r\\n') AS body "
            + "FROM chat_message m WHERE m.conversation_id = c.id AND m.kind = 'text' "
            + "AND btrim(coalesce(m.body, ''), E' \\t\\r\\n') <> '' ORDER BY m.seq DESC LIMIT 1) latest ON true";

    /** The trimmed text cut to the cap, and whether the cut dropped anything; null and false when there is none. */
    private static final String PREVIEW = "left(latest.body, " + PREVIEW_CHARS + ") AS preview, "
            + "coalesce(char_length(latest.body) > " + PREVIEW_CHARS + ", false) AS preview_truncated";

    /** One search word as a LIKE pattern: folded, then escaped in SQL so that %, _ and \ match literally. */
    private static final String WORD_PATTERN = "'%' || replace(replace(replace(chat_search_fold(?), '\\', '\\\\'), "
            + "'%', '\\%'), '_', '\\_') || '%'";

    private static final RowMapper<StoredConversation> CONVERSATION = (rs, rowNum) -> new StoredConversation(
            rs.getObject("id", UUID.class), rs.getString("title"),
            rs.getTimestamp("last_activity_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("pinned"), rs.getObject("folder_id", UUID.class));

    private static final RowMapper<ListedConversation> LISTED = (rs, rowNum) -> new ListedConversation(
            CONVERSATION.mapRow(rs, rowNum), rs.getString("preview"), rs.getBoolean("preview_truncated"));

    private static final RowMapper<GroupCounts> COUNTS = (rs, rowNum) -> new GroupCounts(rs.getInt("total"),
            rs.getInt("pinned"), rs.getInt("today"), rs.getInt("last7_days"), rs.getInt("earlier"));

    private static final RowMapper<StoredMessage> MESSAGE = (rs, rowNum) -> new StoredMessage(
            rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("role"), rs.getString("kind"),
            rs.getString("body"), rs.getString("payload"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    ConversationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record StoredConversation(UUID id, String title, Instant lastActivityAt, Instant createdAt,
            boolean pinned, UUID folderId) {

        /** A conversation that is neither pinned nor in a folder, as every new one is. */
        public StoredConversation(UUID id, String title, Instant lastActivityAt, Instant createdAt) {
            this(id, title, lastActivityAt, createdAt, false, null);
        }
    }

    public record StoredMessage(UUID id, int seq, String role, String kind, String body, String payload,
            String status, Instant createdAt) {
    }

    /**
     * A list row: the conversation and its preview text, null when it has no text message yet.
     * {@code previewTruncated} is true when the trimmed text was longer than the cap.
     */
    public record ListedConversation(StoredConversation conversation, String preview, boolean previewTruncated) {
    }

    /** One page of rows and the cursor of the next page, null on the last page. */
    public record ConversationPage(List<ListedConversation> items, ListCursor next) {
    }

    /** {@code total} is every match; the time groups count unpinned matches only, {@code pinned} every pinned one. */
    public record GroupCounts(int total, int pinned, int today, int last7Days, int earlier) {
    }

    private record Where(String sql, List<Object> args) {
    }

    /**
     * One page of the owner's conversations by activity time then id, in the query's sort, after its cursor. Fetches
     * one row more than the limit to know whether another page exists.
     */
    @Transactional(readOnly = true)
    public ConversationPage list(UUID ownerId, ConversationQuery query) {
        Where where = matched(ownerId, query.words(), query.folder(), query.pinned());
        boolean newest = query.sort() == Sort.NEWEST;
        StringBuilder sql = new StringBuilder("SELECT c.id, c.title, c.last_activity_at, c.created_at, c.pinned, "
                + "c.folder_id, ").append(PREVIEW).append(" FROM chat_conversation c ").append(LATEST_TEXT)
                .append(" WHERE ").append(where.sql());
        List<Object> args = new ArrayList<>(where.args());
        if (query.cursor() != null) {
            sql.append(" AND (c.last_activity_at, c.id) ").append(newest ? "<" : ">")
                    .append(" (CAST(? AS timestamptz), CAST(? AS uuid))");
            args.add(Timestamp.from(query.cursor().lastActivityAt()));
            args.add(query.cursor().id());
        }
        sql.append(newest ? " ORDER BY c.last_activity_at DESC, c.id DESC" : " ORDER BY c.last_activity_at, c.id")
                .append(" LIMIT ?");
        args.add(query.limit() + 1);
        List<ListedConversation> rows = jdbcTemplate.query(sql.toString(), LISTED, args.toArray());
        if (rows.size() <= query.limit()) {
            return new ConversationPage(rows, null);
        }
        List<ListedConversation> items = rows.subList(0, query.limit());
        StoredConversation last = items.getLast().conversation();
        return new ConversationPage(items, new ListCursor(query.sort(), last.lastActivityAt(), last.id()));
    }

    /**
     * Counts the same set {@link #list} walks for these words and folder (any pinned state): every match, the
     * pinned ones, and the unpinned ones from {@code todayStart}, from {@code weekStart} to before it, and before it.
     */
    @Transactional(readOnly = true)
    public GroupCounts counts(UUID ownerId, List<String> words, FolderFilter folder, Instant todayStart,
            Instant weekStart) {
        Where where = matched(ownerId, words, folder, Pinned.ANY);
        String sql = "SELECT count(*) AS total, count(*) FILTER (WHERE c.pinned) AS pinned, "
                + "count(*) FILTER (WHERE NOT c.pinned AND c.last_activity_at >= CAST(? AS timestamptz)) AS today, "
                + "count(*) FILTER (WHERE NOT c.pinned AND c.last_activity_at >= CAST(? AS timestamptz) "
                + "AND c.last_activity_at < CAST(? AS timestamptz)) AS last7_days, "
                + "count(*) FILTER (WHERE NOT c.pinned AND c.last_activity_at < CAST(? AS timestamptz)) AS earlier "
                + "FROM chat_conversation c WHERE " + where.sql();
        List<Object> args = new ArrayList<>();
        Timestamp today = Timestamp.from(todayStart);
        Timestamp week = Timestamp.from(weekStart);
        args.addAll(List.of(today, week, today, week));
        args.addAll(where.args());
        return jdbcTemplate.queryForObject(sql, COUNTS, args.toArray());
    }

    /**
     * The one WHERE both {@link #list} and {@link #counts} use, so a count cannot drift from the rows. A folder id
     * that is not the owner's matches nothing, because the owner test comes with it.
     */
    private static Where matched(UUID ownerId, List<String> words, FolderFilter folder, Pinned pinned) {
        StringBuilder sql = new StringBuilder("c.owner_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (pinned == Pinned.ONLY) {
            sql.append(" AND c.pinned");
        } else if (pinned == Pinned.EXCLUDE) {
            sql.append(" AND NOT c.pinned");
        }
        if (folder.none()) {
            sql.append(" AND c.folder_id IS NULL");
        } else if (folder.id() != null) {
            sql.append(" AND c.folder_id = ?");
            args.add(folder.id());
        }
        for (String word : words) {
            sql.append(" AND (chat_search_fold(c.title) LIKE ").append(WORD_PATTERN)
                    .append(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM chat_message m WHERE m.conversation_id = c.id "
                            + "AND m.kind = 'text' AND chat_search_fold(m.body) LIKE ").append(WORD_PATTERN)
                    .append(" ESCAPE '\\'))");
            args.add(word);
            args.add(word);
        }
        return new Where(sql.toString(), args);
    }

    /** Sets the pin of the owner's conversation without touching its activity time; empty when it is not theirs. */
    @Transactional
    public Optional<StoredConversation> setPinned(UUID ownerId, UUID id, boolean pinned) {
        return jdbcTemplate.query("UPDATE chat_conversation SET pinned = ? WHERE id = ? AND owner_id = ? "
                + "RETURNING " + COLUMNS, CONVERSATION, pinned, id, ownerId).stream().findFirst();
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
        return find(ownerId, id);
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
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM chat_conversation WHERE id = ? AND owner_id = ?",
                CONVERSATION, id, ownerId).stream().findFirst();
    }
}
