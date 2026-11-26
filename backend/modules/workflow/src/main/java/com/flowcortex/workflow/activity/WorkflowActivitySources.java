package com.flowcortex.workflow.activity;

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
 * The workflow module's contribution to a member's activity timeline: the workflows the account created and the
 * runs it started. Neither source names {@code deleted_at}, so a deleted workflow or a run the member removed from
 * their history still shows, and the non-partial {@code (owner_id, time DESC, id DESC)} indexes supply the order.
 */
public final class WorkflowActivitySources {

    private WorkflowActivitySources() {
    }

    /** {@code workflow}: one item per workflow the account owns, at its creation. */
    @Component
    static class Workflows implements UserActivitySource {

        static final String SOURCE = "workflow";

        private final NamedParameterJdbcTemplate jdbc;

        Workflows(NamedParameterJdbcTemplate jdbc) {
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
                    .addValue("owner", accountId)
                    .addValue("limit", limit);
            return jdbc.query("SELECT id, name, created_at FROM workflow WHERE owner_id = :owner"
                    + bound.clause("created_at", "id")
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit", params,
                    (rs, row) -> new UserActivityItem(rs.getObject("id", UUID.class), SOURCE, "workflow.create",
                            UserActivityItem.ACTOR_USER, null, "workflow", rs.getString("id"), rs.getString("name"),
                            null, rs.getTimestamp("created_at").toInstant()));
        }
    }

    /** {@code workflow_run}: one item per run the account started, labelled by its workflow's name. */
    @Component
    static class Runs implements UserActivitySource {

        static final String SOURCE = "workflow_run";

        private final NamedParameterJdbcTemplate jdbc;

        Runs(NamedParameterJdbcTemplate jdbc) {
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
                    .addValue("owner", accountId)
                    .addValue("limit", limit);
            return jdbc.query("SELECT r.id, r.status, r.started_at, r.workflow_id, w.name "
                    + "FROM workflow_run r JOIN workflow w ON w.id = r.workflow_id WHERE r.owner_id = :owner"
                    + bound.clause("r.started_at", "r.id")
                    + " ORDER BY r.started_at DESC, r.id DESC LIMIT :limit", params,
                    (rs, row) -> new UserActivityItem(rs.getObject("id", UUID.class), SOURCE, "workflow_run.start",
                            UserActivityItem.ACTOR_USER, null, "workflow", rs.getString("workflow_id"),
                            rs.getString("name"), rs.getString("status"), rs.getTimestamp("started_at").toInstant()));
        }
    }
}
