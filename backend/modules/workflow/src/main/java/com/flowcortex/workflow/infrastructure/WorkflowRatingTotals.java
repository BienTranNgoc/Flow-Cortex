package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.application.RatingDelta;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The per-workflow review count and star sum behind every catalogue rating. Only ever added to, in the
 * transaction of the review write that changed it, so a rollback takes the change with it and concurrent
 * writers are serialized by the row lock of the upsert.
 */
@Repository
public class WorkflowRatingTotals {

    private final NamedParameterJdbcTemplate jdbc;

    WorkflowRatingTotals(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The inserted tuple is clamped at zero because Postgres checks the table's CHECKs on it before it looks for
     * the conflict, so a negative delta would be refused even though the row it updates exists.
     */
    public void add(UUID workflowId, RatingDelta delta) {
        jdbc.update("""
                INSERT INTO workflow_rating_total (workflow_id, rating_count, rating_sum)
                VALUES (:workflowId, greatest(:count, 0), greatest(:sum, 0))
                ON CONFLICT (workflow_id) DO UPDATE SET
                    rating_count = workflow_rating_total.rating_count + :count,
                    rating_sum = workflow_rating_total.rating_sum + :sum
                """, new MapSqlParameterSource()
                .addValue("workflowId", workflowId)
                .addValue("count", delta.count())
                .addValue("sum", delta.sum()));
    }
}
