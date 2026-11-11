package com.flowcortex.user.infrastructure;

import com.flowcortex.user.api.UserQuery;
import com.flowcortex.user.api.UserStats;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.application.SearchFold;
import com.flowcortex.user.domain.AccountState;
import com.flowcortex.user.domain.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the users of one owner scope for the admin directory: filtered, searched, sorted and paged
 * in the database. Every query shares one {@code WHERE} builder whose first predicate is the scope,
 * and no query selects {@code password_hash}.
 */
@Repository
public class UserDirectoryRepository {

    private static final String SEARCH_EXPRESSION =
            "app_user_search_fold(u.name || ' ' || u.email || ' ' || coalesce(u.username, ''))";

    private static final String SELECT = """
            SELECT u.id, u.owner_id, u.email, u.name, u.role, u.status, u.created_at, u.updated_at, u.username,
                   u.email_confirmed_at, u.locked_until, u.has_password,
                   (u.google_subject IS NOT NULL) AS google_linked,
                   (u.admin_disabled_at IS NOT NULL) AS admin_disabled,
                   (r.staff OR r.super_admin) AS staff_target
            FROM app_user u JOIN role r ON r.code = u.role
            """;

    private static final String SUSPENDED = "u.status = 'SUSPENDED'";
    private static final String PENDING = "u.status = 'ACTIVE' AND u.email_confirmed_at IS NULL";
    private static final String LOCKED =
            "u.status = 'ACTIVE' AND u.email_confirmed_at IS NOT NULL AND u.locked_until > :now";
    private static final String ACTIVE = "u.status = 'ACTIVE' AND u.email_confirmed_at IS NOT NULL"
            + " AND (u.locked_until IS NULL OR u.locked_until <= :now)";

    private record Where(String sql, MapSqlParameterSource params) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public UserDirectoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(UUID scope, UserQuery query, Instant now) {
        Where where = where(scope, query, now);
        Long total = jdbc.queryForObject("SELECT count(*) FROM app_user u WHERE " + where.sql(), where.params(),
                Long.class);
        return total == null ? 0 : total;
    }

    public List<UserView> page(UUID scope, UserQuery query, Instant now, long offset, int limit) {
        Where where = where(scope, query, now);
        where.params().addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(SELECT + "WHERE " + where.sql() + " ORDER BY " + orderBy(query.sort())
                + " LIMIT :limit OFFSET :offset", where.params(), (rs, rowNum) -> view(rs, now));
    }

    public UserStats stats(UUID scope, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("now", utc(now))
                .addValue("since", utc(now.minus(Duration.ofDays(7))));
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE %s) AS active,
                       count(*) FILTER (WHERE %s) AS pending,
                       count(*) FILTER (WHERE %s) AS locked,
                       count(*) FILTER (WHERE %s) AS suspended,
                       count(*) FILTER (WHERE u.created_at >= :since) AS recent
                FROM app_user u WHERE u.owner_id = :scope
                """.formatted(ACTIVE, PENDING, LOCKED, SUSPENDED), params,
                (rs, rowNum) -> new UserStats(rs.getLong("total"), rs.getLong("active"), rs.getLong("pending"),
                        rs.getLong("locked"), rs.getLong("suspended"), rs.getLong("recent")));
    }

    public boolean roleExists(String code) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM role WHERE code = :code)",
                new MapSqlParameterSource("code", code), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** True when the role row is staff or super admin; false for an end-user role or a code no row holds. */
    public boolean staffTarget(String roleCode) {
        Boolean staff = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM role WHERE code = :code AND (staff OR super_admin))",
                new MapSqlParameterSource("code", roleCode), Boolean.class);
        return Boolean.TRUE.equals(staff);
    }

    private static Where where(UUID scope, UserQuery query, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("now", utc(now));
        StringBuilder sql = new StringBuilder("u.owner_id = :scope");
        query.role().ifPresent(role -> {
            sql.append(" AND u.role = :role");
            params.addValue("role", role);
        });
        query.state().ifPresent(state -> sql.append(" AND (").append(predicate(state)).append(')'));
        String folded = SearchFold.fold(query.q());
        if (!folded.isEmpty()) {
            sql.append(" AND ").append(SEARCH_EXPRESSION).append(" LIKE :pattern");
            params.addValue("pattern", "%" + escapeLike(folded) + "%");
        }
        return new Where(sql.toString(), params);
    }

    private static String predicate(AccountState state) {
        return switch (state) {
            case SUSPENDED -> SUSPENDED;
            case PENDING_CONFIRMATION -> PENDING;
            case LOCKED -> LOCKED;
            case ACTIVE -> ACTIVE;
        };
    }

    private static String orderBy(UserQuery.Sort sort) {
        return switch (sort) {
            case NEWEST -> "u.created_at DESC, u.id DESC";
            case OLDEST -> "u.created_at ASC, u.id ASC";
            case EMAIL -> "lower(u.email) ASC, u.id ASC";
            case NAME -> "lower(u.name) ASC, u.id ASC";
        };
    }

    /** Escapes the characters {@code LIKE} treats as wildcards, with the default backslash escape. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UserView view(ResultSet rs, Instant now) throws SQLException {
        UserStatus status = UserStatus.valueOf(rs.getString("status"));
        Instant confirmedAt = instant(rs, "email_confirmed_at");
        Instant lockedUntil = instant(rs, "locked_until");
        return new UserView(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("role"),
                status.name(),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("username"),
                AccountState.of(status, confirmedAt, lockedUntil, now).wire(),
                confirmedAt,
                lockedUntil != null && lockedUntil.isAfter(now) ? lockedUntil : null,
                UserView.signInMethodsOf(rs.getBoolean("has_password"), rs.getBoolean("google_linked")),
                rs.getBoolean("admin_disabled"),
                rs.getBoolean("staff_target"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
