package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The windowed figures the trending and popular orders read: one row per catalogue workflow, written by the
 * discovery pass only. Runs and saves by the workflow's owner never count, and neither does a child run of a
 * workflow-call step.
 */
@Repository
public class WorkflowDiscoveryStats {

    static final int BATCH_SIZE = 500;

    private static final String COMPUTE = "WITH c AS (SELECT w.id, w.owner_id FROM workflow w WHERE "
            + MarketplaceQueries.CATALOGUE + "),"
            + " r7 AS (SELECT r.workflow_id, count(DISTINCT r.owner_id) AS n FROM workflow_run r"
            + "  JOIN c ON c.id = r.workflow_id"
            + "  WHERE r.started_at > :since7 AND r.owner_id <> c.owner_id" + MarketplaceQueries.RUN_SCOPE
            + "  GROUP BY r.workflow_id),"
            + " m30 AS (SELECT r.workflow_id, count(DISTINCT r.owner_id) AS n FROM workflow_run r"
            + "  JOIN c ON c.id = r.workflow_id"
            + "  WHERE r.started_at > :since30 AND r.status = 'completed' AND r.owner_id <> c.owner_id"
            + MarketplaceQueries.RUN_SCOPE + "  GROUP BY r.workflow_id),"
            + " s7 AS (SELECT b.workflow_id, count(*) AS n FROM workflow_bookmark b"
            + "  JOIN c ON c.id = b.workflow_id"
            + "  WHERE b.created_at > :since7 AND b.user_id <> c.owner_id GROUP BY b.workflow_id)"
            + " SELECT c.id, COALESCE(r7.n, 0) AS runners_7d, COALESCE(s7.n, 0) AS saves_7d,"
            + " COALESCE(m30.n, 0) AS members_30d FROM c"
            + " LEFT JOIN r7 ON r7.workflow_id = c.id LEFT JOIN s7 ON s7.workflow_id = c.id"
            + " LEFT JOIN m30 ON m30.workflow_id = c.id";

    private static final String UPSERT = """
            INSERT INTO workflow_discovery_stats
                (workflow_id, runners_7d, saves_7d, members_30d, trending_score, computed_at)
            VALUES (:workflowId, :runners7d, :saves7d, :members30d, :trendingScore, :now)
            ON CONFLICT (workflow_id) DO UPDATE SET
                runners_7d = EXCLUDED.runners_7d, saves_7d = EXCLUDED.saves_7d,
                members_30d = EXCLUDED.members_30d, trending_score = EXCLUDED.trending_score,
                computed_at = EXCLUDED.computed_at
            """;

    /** What the windows counted for one catalogue workflow, before its score. */
    public record Counts(UUID workflowId, int runners7d, int saves7d, int members30d) {
    }

    /** One row of {@code workflow_discovery_stats}. */
    public record Row(UUID workflowId, int runners7d, int saves7d, int members30d, int trendingScore) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean productVideoOn;

    public WorkflowDiscoveryStats(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${flowcortex.product-video.enabled:false}") boolean productVideoOn) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.productVideoOn = productVideoOn;
    }

    /** The counts of every catalogue workflow at {@code now}; a workflow with no activity counts zero. */
    public List<Counts> compute(Instant now) {
        return jdbc.query(COMPUTE, catalogue()
                .addValue("since7", OffsetDateTime.ofInstant(now.minusSeconds(7 * 86_400L), ZoneOffset.UTC))
                .addValue("since30", OffsetDateTime.ofInstant(now.minusSeconds(30 * 86_400L), ZoneOffset.UTC)),
                (rs, i) -> new Counts(rs.getObject("id", UUID.class), rs.getInt("runners_7d"),
                        rs.getInt("saves_7d"), rs.getInt("members_30d")));
    }

    /** Writes the rows in batches of {@value #BATCH_SIZE}, each batch in its own short transaction. */
    public void upsert(List<Row> rows, Instant now) {
        OffsetDateTime stamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            List<Row> batch = rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()));
            SqlParameterSource[] params = batch.stream().map(row -> new MapSqlParameterSource()
                    .addValue("workflowId", row.workflowId())
                    .addValue("runners7d", row.runners7d())
                    .addValue("saves7d", row.saves7d())
                    .addValue("members30d", row.members30d())
                    .addValue("trendingScore", row.trendingScore())
                    .addValue("now", stamp)).toArray(SqlParameterSource[]::new);
            transaction.executeWithoutResult(status -> jdbc.batchUpdate(UPSERT, params));
        }
    }

    /** Deletes the rows of workflows no longer in the catalogue; returns how many. */
    public int removeOutsideCatalogue() {
        return jdbc.update("DELETE FROM workflow_discovery_stats WHERE workflow_id NOT IN"
                + " (SELECT w.id FROM workflow w WHERE " + MarketplaceQueries.CATALOGUE + ")", catalogue());
    }

    private MapSqlParameterSource catalogue() {
        return new MapSqlParameterSource()
                .addValue("productVideoOn", productVideoOn)
                .addValue("productVideoId", ProductVideoWorkflow.ID);
    }
}
