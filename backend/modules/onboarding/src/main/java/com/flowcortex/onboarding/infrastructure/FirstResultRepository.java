package com.flowcortex.onboarding.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The once-per-account first-result report; the primary key on {@code user_id} decides every race. */
@Repository
public class FirstResultRepository {

    /** The run that was reported. */
    public record Reported(UUID runId, UUID workflowId) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    FirstResultRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID userId) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM first_result_report WHERE user_id = :userId)",
                new MapSqlParameterSource("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /** Stores the report; empty when the account already has one (another claim or the backfill won). */
    public Optional<Reported> insertClaim(UUID userId, UUID runId, UUID workflowId, Instant finishedAt, Instant now) {
        List<Reported> rows = jdbc.query("""
                INSERT INTO first_result_report (user_id, run_id, workflow_id, source, result_at, reported_at)
                VALUES (:userId, :runId, :workflowId, 'claim', :finishedAt, :now)
                ON CONFLICT (user_id) DO NOTHING
                RETURNING run_id, workflow_id
                """,
                new MapSqlParameterSource().addValue("userId", userId).addValue("runId", runId)
                        .addValue("workflowId", workflowId).addValue("finishedAt", Timestamp.from(finishedAt))
                        .addValue("now", Timestamp.from(now)),
                (rs, row) -> new Reported(rs.getObject("run_id", UUID.class),
                        rs.getObject("workflow_id", UUID.class)));
        return rows.stream().findFirst();
    }
}
