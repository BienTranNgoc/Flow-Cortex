package com.flowcortex.user.activity;

import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.ActivityCursor.Bound;
import com.flowcortex.user.api.UserActivityItem;
import com.flowcortex.user.api.UserActivitySource;
import com.flowcortex.user.pii.PiiMask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What admins did to the account: audit rows about a {@code user} or {@code admin_account} with the account's id.
 * The resource predicate is the one of the partial index {@code idx_admin_audit_event_member_page}, spelled
 * identically so the planner reads that index in order.
 */
@Component
class AdminActionActivitySource implements UserActivitySource {

    static final String SOURCE = "admin_action";
    private static final String USER_RESOURCE = "user";

    private static final RowMapper<UserActivityItem> ITEM = AdminActionActivitySource::item;

    private final NamedParameterJdbcTemplate jdbc;

    AdminActionActivitySource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<UserActivityItem> page(UUID accountId, ActivityCursor after, int limit) {
        Bound bound = ActivityCursor.boundFor(after, SOURCE);
        MapSqlParameterSource params = new MapSqlParameterSource(bound.params())
                .addValue("id", accountId.toString())
                .addValue("limit", limit);
        return jdbc.query("SELECT id, action, actor_name, actor_email, resource_type, resource_id, resource_label, "
                + "status, occurred_at FROM admin_audit_event "
                + "WHERE resource_type IN ('user', 'admin_account') AND resource_id = :id"
                + bound.clause("occurred_at", "id")
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit", params, ITEM);
    }

    private static UserActivityItem item(ResultSet rs, int row) throws SQLException {
        String resourceType = rs.getString("resource_type");
        String label = rs.getString("resource_label");
        String actorName = rs.getString("actor_name");
        return new UserActivityItem(rs.getObject("id", UUID.class), SOURCE, rs.getString("action"),
                UserActivityItem.ACTOR_ADMIN, actorName != null ? actorName : rs.getString("actor_email"),
                resourceType, rs.getString("resource_id"),
                USER_RESOURCE.equals(resourceType) ? PiiMask.label(label) : label, rs.getString("status"),
                rs.getTimestamp("occurred_at").toInstant());
    }
}
