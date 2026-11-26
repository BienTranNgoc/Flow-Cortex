package com.flowcortex.user.activity;

import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.ActivityCursor.Bound;
import com.flowcortex.user.api.UserActivityItem;
import com.flowcortex.user.api.UserActivitySource;
import com.flowcortex.user.application.AuthRetentionProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The account's own auth events within the auth retention window, so a row the retention job has not yet purged
 * does not show. The {@code (user_id, occurred_at)} index orders by time; the id tie-break is an incremental sort
 * over the rows of one instant.
 */
@Component
class AuthActivitySource implements UserActivitySource {

    static final String SOURCE = "auth";

    private static final RowMapper<UserActivityItem> ITEM = AuthActivitySource::item;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuthRetentionProperties retention;
    private final Clock clock;

    AuthActivitySource(NamedParameterJdbcTemplate jdbc, AuthRetentionProperties retention, Clock clock) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<UserActivityItem> page(UUID accountId, ActivityCursor after, int limit) {
        Bound bound = ActivityCursor.boundFor(after, SOURCE);
        Instant floor = Instant.now(clock).minus(retention.auditEvents());
        MapSqlParameterSource params = new MapSqlParameterSource(bound.params())
                .addValue("id", accountId)
                .addValue("floor", Timestamp.from(floor))
                .addValue("limit", limit);
        return jdbc.query("SELECT id, type, occurred_at FROM auth_event WHERE user_id = :id"
                + " AND occurred_at >= :floor" + bound.clause("occurred_at", "id")
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit", params, ITEM);
    }

    private static UserActivityItem item(ResultSet rs, int row) throws SQLException {
        return new UserActivityItem(rs.getObject("id", UUID.class), SOURCE,
                "auth." + rs.getString("type").toLowerCase(Locale.ROOT), UserActivityItem.ACTOR_USER, null, null,
                null, null, null, rs.getTimestamp("occurred_at").toInstant());
    }
}
