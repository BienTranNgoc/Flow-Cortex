package com.flowcortex.entity.infrastructure;

import com.flowcortex.shared.web.PageView;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code space_entity_run}: which run used which entity at which version, and when that run completed. */
@Repository
public class EntityRunStore {

    /** One completed use of an entity. {@code workflowId} and {@code workflowName} are null when the run had none. */
    public record CompletedRun(UUID runId, UUID workflowId, String workflowName, int version, Instant completedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public EntityRunStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Writes a usage row, at the entity's current version, for each id that is a live entity of the space and has
     * none for this run yet. An existing row is left as it is.
     *
     * @param completedAt the run's finish time when it already completed, else {@code null}
     * @return the rows written
     */
    public int insertLiveIfAbsent(UUID runId, UUID spaceId, Collection<UUID> entityIds, UUID workflowId,
            String workflowName, Instant completedAt) {
        if (entityIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("INSERT INTO space_entity_run (run_id, entity_id, space_id, version, workflow_id, "
                + "workflow_name, created_at, completed_at) "
                + "SELECT :run, e.id, e.space_id, e.version, :workflowId, :workflowName, :now, :completedAt "
                + "FROM space_entity e WHERE e.id IN (:ids) AND e.space_id = :space AND e.deleted_at IS NULL "
                + "ON CONFLICT (run_id, entity_id) DO NOTHING",
                new MapSqlParameterSource("run", runId)
                        .addValue("space", spaceId)
                        .addValue("ids", entityIds)
                        .addValue("workflowId", workflowId)
                        .addValue("workflowName", workflowName)
                        .addValue("now", Timestamp.from(clock.instant()))
                        .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt)));
    }

    /**
     * Completes every usage row of the run that is not completed yet; a row that already has a time keeps it.
     *
     * @return the rows completed
     */
    public int markCompleted(UUID runId, Instant at) {
        return jdbc.update("UPDATE space_entity_run SET completed_at = :at "
                + "WHERE run_id = :run AND completed_at IS NULL",
                new MapSqlParameterSource("run", runId).addValue("at", Timestamp.from(at)));
    }

    /** The completed uses of an entity, newest completion first, run id breaking ties. */
    public PageView<CompletedRun> pageCompleted(UUID entityId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource("entity", entityId);
        Long total = jdbc.queryForObject("SELECT count(*) FROM space_entity_run "
                + "WHERE entity_id = :entity AND completed_at IS NOT NULL", params, Long.class);
        List<CompletedRun> items = jdbc.query("SELECT run_id, workflow_id, workflow_name, version, completed_at "
                + "FROM space_entity_run WHERE entity_id = :entity AND completed_at IS NOT NULL "
                + "ORDER BY completed_at DESC, run_id LIMIT :limit OFFSET :offset",
                params.addValue("limit", size).addValue("offset", (long) page * size),
                (rs, i) -> new CompletedRun(rs.getObject("run_id", UUID.class),
                        rs.getObject("workflow_id", UUID.class), rs.getString("workflow_name"), rs.getInt("version"),
                        rs.getTimestamp("completed_at").toInstant()));
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }
}
