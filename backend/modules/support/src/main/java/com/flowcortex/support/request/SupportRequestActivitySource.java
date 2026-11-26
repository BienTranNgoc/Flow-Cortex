package com.flowcortex.support.request;

import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.ActivityCursor.Bound;
import com.flowcortex.user.api.UserActivityItem;
import com.flowcortex.user.api.UserActivitySource;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The support requests the account filed, at their creation, labelled {@code #<number> <subject>}. Read through
 * {@code idx_support_request_requester_created_page}; the existing requester index orders by last activity.
 */
@Component
class SupportRequestActivitySource implements UserActivitySource {

    static final String SOURCE = "support_request";

    private final NamedParameterJdbcTemplate jdbc;

    SupportRequestActivitySource(NamedParameterJdbcTemplate jdbc) {
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
                .addValue("requester", accountId)
                .addValue("limit", limit);
        return jdbc.query("SELECT id, number, subject, created_at FROM support_request WHERE requester_id = :requester"
                + bound.clause("created_at", "id")
                + " ORDER BY created_at DESC, id DESC LIMIT :limit", params,
                (rs, row) -> new UserActivityItem(rs.getObject("id", UUID.class), SOURCE, "support_request.create",
                        UserActivityItem.ACTOR_USER, null, "support_request", rs.getString("id"),
                        "#" + rs.getLong("number") + " " + rs.getString("subject"), null,
                        rs.getTimestamp("created_at").toInstant()));
    }
}
