package com.flowcortex.mail.links;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence of the one link row per member and the history of unsubscribes. */
@Repository
class UnsubscribeLinkRepository {

    /** A link row as read: the member it belongs to and which lists that member has left. */
    record LinkRow(UUID id, UUID memberId, LinkState state) {
    }

    private static final String INSERT_LINK = """
            INSERT INTO mail_unsubscribe_link (id, user_id, created_at)
            VALUES (:id, :userId, now())
            ON CONFLICT (user_id) DO NOTHING
            RETURNING id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    UnsubscribeLinkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The member's link id, created on first use. Two concurrent first calls leave exactly one row: the
     * loser of the conflict waits for the winner's commit and then reads its id.
     */
    UUID ensureLinkId(UUID memberId) {
        List<UUID> created = jdbc.query(INSERT_LINK,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("userId", memberId),
                (rs, row) -> rs.getObject("id", UUID.class));
        if (!created.isEmpty()) {
            return created.getFirst();
        }
        return jdbc.queryForObject("SELECT id FROM mail_unsubscribe_link WHERE user_id = :userId",
                new MapSqlParameterSource("userId", memberId), UUID.class);
    }

    LinkState state(UUID memberId) {
        List<LinkState> rows = jdbc.query("""
                SELECT care_unsubscribed_at IS NOT NULL AS care, marketing_unsubscribed_at IS NOT NULL AS marketing
                FROM mail_unsubscribe_link WHERE user_id = :userId
                """, new MapSqlParameterSource("userId", memberId),
                (rs, row) -> new LinkState(rs.getBoolean("care"), rs.getBoolean("marketing")));
        return rows.isEmpty() ? LinkState.NONE : rows.getFirst();
    }

    Optional<LinkRow> find(UUID linkId) {
        return jdbc.query("""
                SELECT id, user_id, care_unsubscribed_at IS NOT NULL AS care,
                       marketing_unsubscribed_at IS NOT NULL AS marketing
                FROM mail_unsubscribe_link WHERE id = :id
                """, new MapSqlParameterSource("id", linkId),
                (rs, row) -> new LinkRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        new LinkState(rs.getBoolean("care"), rs.getBoolean("marketing")))).stream().findFirst();
    }

    /**
     * Leaves the list and records it in one statement, so the two writes commit together. The conditional
     * update is the idempotency guard: only the call that flips the column writes a history row.
     *
     * @param mailKey the catalog key the link came from, or null when the caller named none
     * @return whether this call changed anything
     */
    boolean apply(UUID linkId, UUID memberId, LinkList list, LinkSource source, String mailKey) {
        return flip(linkId, memberId, list, source, mailKey, "now()", "IS NULL", "unsubscribe");
    }

    /**
     * Rejoins the list with the same one-statement guard as {@link #apply}: only the call that clears a set
     * column writes the history row.
     *
     * @return whether this call changed anything
     */
    boolean clear(UUID linkId, UUID memberId, LinkList list, LinkSource source) {
        return flip(linkId, memberId, list, source, null, "NULL", "IS NOT NULL", "resubscribe");
    }

    /** {@code newValue}, {@code guard} and {@code action} are constants of the two callers, never user input. */
    private boolean flip(UUID linkId, UUID memberId, LinkList list, LinkSource source, String mailKey,
            String newValue, String guard, String action) {
        List<UUID> recorded = jdbc.query("WITH flipped AS (UPDATE mail_unsubscribe_link SET " + list.column()
                        + " = " + newValue + " WHERE id = :id AND " + list.column() + " " + guard + " RETURNING id) "
                        + "INSERT INTO mail_unsubscribe_history (id, user_id, list, source, mail_key, action, "
                        + "created_at) SELECT :historyId, :userId, :list, :source, :mailKey, :action, now() "
                        + "FROM flipped RETURNING id",
                new MapSqlParameterSource("id", linkId).addValue("historyId", UUID.randomUUID())
                        .addValue("userId", memberId).addValue("list", list.wire())
                        .addValue("source", source.wire()).addValue("mailKey", mailKey, Types.VARCHAR)
                        .addValue("action", action),
                (rs, row) -> rs.getObject("id", UUID.class));
        return !recorded.isEmpty();
    }
}
