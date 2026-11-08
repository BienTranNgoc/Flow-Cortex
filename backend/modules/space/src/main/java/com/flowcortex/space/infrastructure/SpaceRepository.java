package com.flowcortex.space.infrastructure;

import com.flowcortex.space.api.SpaceContext;
import com.flowcortex.space.api.SpaceRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Personal spaces and their memberships, created exactly once by the database's unique indexes. */
@Repository
public class SpaceRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    SpaceRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The owner's personal space with the owner's membership role, or empty when either row is missing. */
    public Optional<SpaceContext> findPersonal(UUID ownerId) {
        List<SpaceContext> found = jdbc.query("SELECT s.id, m.role FROM space s "
                + "JOIN space_member m ON m.space_id = s.id AND m.user_id = s.owner_id "
                + "WHERE s.owner_id = :owner AND s.is_default",
                new MapSqlParameterSource("owner", ownerId),
                (rs, row) -> new SpaceContext(rs.getObject("id", UUID.class), role(rs.getString("role"))));
        return found.stream().findFirst();
    }

    /** Creates the personal space and its manager membership if missing, repairs a missing membership, returns the result. */
    public SpaceContext ensurePersonal(UUID ownerId) {
        Optional<SpaceContext> existing = findPersonal(ownerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("owner", ownerId)
                .addValue("id", UUID.randomUUID())
                .addValue("now", Timestamp.from(clock.instant()));
        // ON CONFLICT repeats the index predicate so Postgres can infer the partial unique index.
        jdbc.update("WITH s AS (INSERT INTO space (id, owner_id, is_default, created_at) "
                + "VALUES (:id, :owner, true, :now) "
                + "ON CONFLICT (owner_id) WHERE is_default DO NOTHING RETURNING id) "
                + "INSERT INTO space_member (space_id, user_id, role, created_at) "
                + "SELECT id, :owner, 'manager', :now FROM s", params);
        jdbc.update("INSERT INTO space_member (space_id, user_id, role, created_at) "
                + "SELECT s.id, :owner, 'manager', :now FROM space s WHERE s.owner_id = :owner AND s.is_default "
                + "ON CONFLICT (space_id, user_id) DO NOTHING", params);
        return findPersonal(ownerId).orElseThrow(
                () -> new IllegalStateException("Personal space missing after ensure for owner " + ownerId));
    }

    /** The role {@code userId} holds in {@code spaceId}, or empty for a non-member or an unknown space. */
    public Optional<SpaceRole> findMemberRole(UUID spaceId, UUID userId) {
        List<SpaceRole> roles = jdbc.query("SELECT role FROM space_member WHERE space_id = :space AND user_id = :user",
                new MapSqlParameterSource("space", spaceId).addValue("user", userId),
                (rs, row) -> role(rs.getString("role")));
        return roles.stream().findFirst();
    }

    private static SpaceRole role(String column) {
        return SpaceRole.valueOf(column.toUpperCase(Locale.ROOT));
    }
}
