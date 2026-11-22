package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.api.RunRecordStep;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The two reads behind {@code RunRecords}: the run row with its workflow name, and its ordered steps. */
@Repository
public class RunRecordQueries {

    /** A run row; {@code workflowName} and {@code productName} are null when no such row joins. */
    public record RunRow(UUID runId, UUID ownerId, UUID workflowId, String workflowName, String status,
            String input, String productName, Instant finishedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    RunRecordQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunRow> findRun(UUID runId) {
        return jdbc.query("SELECT r.id, r.owner_id, r.workflow_id, w.name AS workflow_name, r.status, r.input, "
                + "p.product_name, r.finished_at FROM workflow_run r "
                + "LEFT JOIN workflow w ON w.id = r.workflow_id "
                + "LEFT JOIN product_video_input p ON p.run_id = r.id WHERE r.id = :run",
                new MapSqlParameterSource("run", runId),
                (rs, row) -> {
                    Timestamp finished = rs.getTimestamp("finished_at");
                    return new RunRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                            rs.getObject("workflow_id", UUID.class), rs.getString("workflow_name"),
                            rs.getString("status"), rs.getString("input"), rs.getString("product_name"),
                            finished == null ? null : finished.toInstant());
                }).stream().findFirst();
    }

    public List<RunRecordStep> findSteps(UUID runId) {
        return jdbc.query("SELECT id, step_order, type, attempt FROM run_step WHERE run_id = :run "
                + "ORDER BY step_order",
                new MapSqlParameterSource("run", runId),
                (rs, row) -> new RunRecordStep(rs.getObject("id", UUID.class), rs.getInt("step_order"),
                        rs.getString("type"), rs.getShort("attempt")));
    }
}
