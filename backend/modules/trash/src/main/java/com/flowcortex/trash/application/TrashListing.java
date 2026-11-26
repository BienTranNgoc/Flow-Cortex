package com.flowcortex.trash.application;

import com.flowcortex.trash.domain.TrashClock;
import com.flowcortex.trash.domain.TrashText;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads one member's Trash: a page of entries, the number matching the filter, and the badge counts. Only entries of
 * types a provider is registered for are visible, so an entry whose module no longer plugs in stays out of the list
 * and the counts until it does.
 */
@Service
public class TrashListing {

    /** The key of the count over every provided type. */
    public static final String ALL = "all";

    public enum Sort {
        /** Latest delete first. */
        RECENT("deleted_at DESC, id"),
        /** Closest to expiry first, which is the oldest delete. */
        EXPIRING("deleted_at ASC, id");

        private final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    /**
     * @param type a provided or unprovided type key, null for every provided type
     * @param nameKey the text key of the search, empty for no search
     */
    public record Query(String type, String nameKey, Sort sort, int page, int size) {
    }

    /** @param name the stored name, null for an untitled item */
    public record Entry(String type, String itemId, String name, String locationCode, Instant deletedAt,
            Instant expiresAt, int remainingDays) {
    }

    /** @param counts one key per provided type, in purge order, then {@link #ALL}; whatever the query filters */
    public record Result(List<Entry> items, long total, Map<String, Long> counts) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TrashProviders providers;
    private final TrashProperties properties;
    private final Clock clock;

    public TrashListing(NamedParameterJdbcTemplate jdbc, TrashProviders providers, TrashProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.properties = properties;
        this.clock = clock;
    }

    public Result list(UUID ownerId, Query query) {
        List<String> provided = providers.types();
        Map<String, Long> counts = counts(ownerId, provided);
        if (provided.isEmpty() || query.type() != null && !provided.contains(query.type())) {
            return new Result(List.of(), 0, counts);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("owner", ownerId)
                .addValue("types", query.type() == null ? provided : List.of(query.type()));
        String where = "owner_id = :owner AND item_type IN (:types)";
        if (!query.nameKey().isEmpty()) {
            where += " AND name_key LIKE :pattern";
            params.addValue("pattern", TrashText.containsPattern(query.nameKey()));
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE " + where, params, Long.class);
        if (total == null || total == 0) {
            return new Result(List.of(), 0, counts);
        }
        params.addValue("limit", query.size()).addValue("offset", (long) query.page() * query.size());
        Instant now = clock.instant();
        List<Entry> items = jdbc.query("SELECT item_type, item_id, name, location_code, deleted_at FROM trash_entry "
                + "WHERE " + where + " ORDER BY " + query.sort().orderBy + " LIMIT :limit OFFSET :offset", params,
                (rs, row) -> {
                    Instant deletedAt = rs.getTimestamp("deleted_at").toInstant();
                    Instant expiresAt = TrashClock.expiresAt(deletedAt, properties.retention());
                    return new Entry(rs.getString("item_type"), rs.getString("item_id"), rs.getString("name"),
                            rs.getString("location_code"), deletedAt, expiresAt,
                            TrashClock.remainingDays(expiresAt, now));
                });
        return new Result(items, total, counts);
    }

    private Map<String, Long> counts(UUID ownerId, List<String> provided) {
        Map<String, Long> counts = new LinkedHashMap<>();
        provided.forEach(type -> counts.put(type, 0L));
        long all = 0;
        if (!provided.isEmpty()) {
            List<Map.Entry<String, Long>> grouped = jdbc.query("SELECT item_type, count(*) AS n FROM trash_entry "
                    + "WHERE owner_id = :owner AND item_type IN (:types) GROUP BY item_type",
                    new MapSqlParameterSource("owner", ownerId).addValue("types", provided),
                    (rs, row) -> Map.entry(rs.getString("item_type"), rs.getLong("n")));
            for (Map.Entry<String, Long> group : grouped) {
                counts.put(group.getKey(), group.getValue());
                all += group.getValue();
            }
        }
        counts.put(ALL, all);
        return counts;
    }
}
