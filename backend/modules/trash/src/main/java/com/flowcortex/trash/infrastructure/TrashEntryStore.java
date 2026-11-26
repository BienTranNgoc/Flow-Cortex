package com.flowcortex.trash.infrastructure;

import com.flowcortex.trash.api.TrashRecord;
import com.flowcortex.trash.domain.EmptyCursor;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code trash_entry} table. Every call joins the caller's transaction; none opens one. */
@Repository
public class TrashEntryStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public TrashEntryStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Writes the entry of a freshly deleted item. An item that already has one is refreshed in place: new owner,
     * name and location, delete time now, reminder cleared, so the item gets its full retention again.
     *
     * @param name the display name, null for an untitled item
     * @param nameKey the search key of {@code name}, empty for an untitled item
     */
    public void upsert(TrashRecord record, String name, String nameKey) {
        jdbc.update("INSERT INTO trash_entry (id, item_type, item_id, owner_id, deleted_by, name, name_key, "
                + "location_code, deleted_at, reminded_at) "
                + "VALUES (:id, :type, :itemId, :owner, :deletedBy, :name, :nameKey, :location, :now, NULL) "
                + "ON CONFLICT (item_type, item_id) DO UPDATE SET owner_id = EXCLUDED.owner_id, "
                + "deleted_by = EXCLUDED.deleted_by, name = EXCLUDED.name, name_key = EXCLUDED.name_key, "
                + "location_code = EXCLUDED.location_code, deleted_at = EXCLUDED.deleted_at, reminded_at = NULL",
                new MapSqlParameterSource("id", UUID.randomUUID())
                        .addValue("type", record.type())
                        .addValue("itemId", record.itemId())
                        .addValue("owner", record.ownerId())
                        .addValue("deletedBy", record.deletedBy())
                        .addValue("name", name, Types.VARCHAR)
                        .addValue("nameKey", nameKey)
                        .addValue("location", record.locationCode())
                        .addValue("now", Timestamp.from(clock.instant())));
    }

    /** Drops the item's entry; false when it had none. */
    public boolean deleteByItem(String type, String itemId) {
        return jdbc.update("DELETE FROM trash_entry WHERE item_type = :type AND item_id = :itemId",
                new MapSqlParameterSource("type", type).addValue("itemId", itemId)) > 0;
    }

    /**
     * Locks the entry of {@code ownerId}'s item until the transaction ends, serializing every action on it. Empty
     * when the item has no entry or belongs to another account.
     */
    public Optional<UUID> lockOwned(String type, String itemId, UUID ownerId) {
        return jdbc.queryForList("SELECT id FROM trash_entry WHERE item_type = :type AND item_id = :itemId "
                + "AND owner_id = :owner FOR UPDATE",
                new MapSqlParameterSource("type", type).addValue("itemId", itemId).addValue("owner", ownerId),
                UUID.class).stream().findFirst();
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM trash_entry WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /** One entry of an empty run: what the provider is asked about, and where the entry sits in the run's order. */
    public record OwnedEntry(UUID id, String type, String itemId, int rank, Instant deletedAt) {

        public EmptyCursor position() {
            return new EmptyCursor(rank, deletedAt, id);
        }
    }

    /**
     * The next {@code limit} entries of {@code ownerId} whose type is a key of {@code rankByType}, ordered by that
     * rank, delete time and id, strictly after {@code after}.
     */
    public List<OwnedEntry> ownedByRankAfter(UUID ownerId, Map<String, Integer> rankByType,
            Optional<EmptyCursor> after, int limit) {
        if (rankByType.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("owner", ownerId)
                .addValue("types", rankByType.keySet()).addValue("limit", limit);
        StringBuilder rank = new StringBuilder("CASE item_type");
        int index = 0;
        for (Map.Entry<String, Integer> entry : rankByType.entrySet()) {
            rank.append(" WHEN :type").append(index).append(" THEN CAST(:rank").append(index).append(" AS integer)");
            params.addValue("type" + index, entry.getKey()).addValue("rank" + index, entry.getValue());
            index++;
        }
        rank.append(" END");
        String position = "";
        if (after.isPresent()) {
            position = "WHERE (purge_rank, deleted_at, id) > (CAST(:afterRank AS integer), "
                    + "CAST(:afterDeletedAt AS timestamptz), CAST(:afterId AS uuid)) ";
            params.addValue("afterRank", after.get().rank())
                    .addValue("afterDeletedAt", Timestamp.from(after.get().deletedAt()))
                    .addValue("afterId", after.get().id());
        }
        return jdbc.query("SELECT id, item_type, item_id, purge_rank, deleted_at FROM ("
                + "SELECT id, item_type, item_id, deleted_at, " + rank + " AS purge_rank FROM trash_entry "
                + "WHERE owner_id = :owner AND item_type IN (:types)) entries " + position
                + "ORDER BY purge_rank, deleted_at, id LIMIT :limit", params,
                (rs, row) -> new OwnedEntry(rs.getObject("id", UUID.class), rs.getString("item_type"),
                        rs.getString("item_id"), rs.getInt("purge_rank"),
                        rs.getTimestamp("deleted_at").toInstant()));
    }

    /** An expired entry the sweep picked: its id, the item it names and where it sits in the sweep's order. */
    public record ExpiredEntry(UUID id, String itemId, Instant deletedAt) {
    }

    /** The entry the sweep holds locked: what the provider is asked to purge. */
    public record LockedEntry(String type, String itemId, UUID ownerId) {
    }

    /**
     * The next {@code limit} entries of {@code type} deleted before {@code cutoff}, oldest first with ties by id,
     * strictly after {@code after}. With {@code remindedNoLaterThan} only entries reminded by then are returned.
     */
    public List<ExpiredEntry> expiredBatch(String type, Instant cutoff, Optional<Instant> remindedNoLaterThan,
            Optional<ExpiredEntry> after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("type", type)
                .addValue("cutoff", Timestamp.from(cutoff)).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("SELECT id, item_id, deleted_at FROM trash_entry "
                + "WHERE item_type = :type AND deleted_at < :cutoff");
        remindedNoLaterThan.ifPresent(reminded -> {
            sql.append(" AND reminded_at <= :remindedBy");
            params.addValue("remindedBy", Timestamp.from(reminded));
        });
        after.ifPresent(position -> {
            sql.append(" AND (deleted_at, id) > (CAST(:afterDeletedAt AS timestamptz), CAST(:afterId AS uuid))");
            params.addValue("afterDeletedAt", Timestamp.from(position.deletedAt())).addValue("afterId", position.id());
        });
        sql.append(" ORDER BY deleted_at, id LIMIT :limit");
        return jdbc.query(sql.toString(), params, (rs, row) -> new ExpiredEntry(rs.getObject("id", UUID.class),
                rs.getString("item_id"), rs.getTimestamp("deleted_at").toInstant()));
    }

    /**
     * Locks the entry until the transaction ends if it is still expired, still reminded long enough when
     * {@code remindedNoLaterThan} is given, and no other transaction holds it. Empty otherwise: the entry was
     * dropped, re-recorded, or is being restored or purged by someone else.
     */
    public Optional<LockedEntry> lockExpired(UUID id, Instant cutoff, Optional<Instant> remindedNoLaterThan) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("cutoff", Timestamp.from(cutoff));
        StringBuilder sql = new StringBuilder("SELECT item_type, item_id, owner_id FROM trash_entry "
                + "WHERE id = :id AND deleted_at < :cutoff");
        remindedNoLaterThan.ifPresent(reminded -> {
            sql.append(" AND reminded_at <= :remindedBy");
            params.addValue("remindedBy", Timestamp.from(reminded));
        });
        sql.append(" FOR UPDATE SKIP LOCKED");
        return jdbc.query(sql.toString(), params, (rs, row) -> new LockedEntry(rs.getString("item_type"),
                rs.getString("item_id"), rs.getObject("owner_id", UUID.class))).stream().findFirst();
    }

    /** How many entries deleted before {@code cutoff} are of a type not in {@code types}. */
    public long countExpiredOutside(Collection<String> types, Instant cutoff) {
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", Timestamp.from(cutoff));
        String sql = "SELECT count(*) FROM trash_entry WHERE deleted_at < :cutoff";
        if (!types.isEmpty()) {
            sql += " AND item_type NOT IN (:types)";
            params.addValue("types", types);
        }
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** How many entries of {@code types} deleted before {@code cutoff} have never been reminded. */
    public long countExpiredUnreminded(Collection<String> types, Instant cutoff) {
        if (types.isEmpty()) {
            return 0;
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE deleted_at < :cutoff "
                + "AND reminded_at IS NULL AND item_type IN (:types)",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("types", types), Long.class);
        return count == null ? 0 : count;
    }

    /** An entry a reminder pass claimed: whose it is and when it was deleted. */
    public record ClaimedEntry(UUID ownerId, Instant deletedAt) {
    }

    /**
     * Marks as reminded, at {@code now}, up to {@code limit} never-reminded entries of {@code types} deleted at or
     * before {@code dueBy}, oldest first. There is no lower bound: an entry already past its expiry is claimed too.
     * Entries another transaction holds are skipped, so two passes never claim the same entry.
     */
    public List<ClaimedEntry> claimForReminder(Collection<String> types, Instant dueBy, Instant now, int limit) {
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbc.query("UPDATE trash_entry SET reminded_at = :now WHERE id IN ("
                + "SELECT id FROM trash_entry WHERE item_type IN (:types) AND reminded_at IS NULL "
                + "AND deleted_at <= :dueBy ORDER BY deleted_at, id LIMIT :limit FOR UPDATE SKIP LOCKED) "
                + "RETURNING owner_id, deleted_at",
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("types", types)
                        .addValue("dueBy", Timestamp.from(dueBy)).addValue("limit", limit),
                (rs, row) -> new ClaimedEntry(rs.getObject("owner_id", UUID.class),
                        rs.getTimestamp("deleted_at").toInstant()));
    }

    /** How many entries {@code ownerId} holds of the given types. */
    public long countOwned(UUID ownerId, Collection<String> types) {
        if (types.isEmpty()) {
            return 0;
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE owner_id = :owner "
                + "AND item_type IN (:types)", new MapSqlParameterSource("owner", ownerId).addValue("types", types),
                Long.class);
        return count == null ? 0 : count;
    }
}
