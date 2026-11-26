package com.flowcortex.support.request;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SLA alert ledger and the read that feeds the sweep. A claim is one row per (request, level, cycle,
 * assignee); the unique key makes it the only thing that lets an alert go out once.
 */
@Repository
class SlaAlertRepository {

    private static final RowMapper<SlaRow> ROW = (rs, n) -> new SlaRow(rs.getObject("id", UUID.class),
            rs.getLong("number"), rs.getString("subject"), RequestStatus.of(rs.getString("status")),
            rs.getObject("assignee_id", UUID.class), rs.getTimestamp("last_activity_at").toInstant());

    /**
     * Fresh crossings only, so a backlog never reaches the batch cap: a row is read when it crossed its limit (it
     * emits {@code overdue}) or only its warning (it emits {@code due_soon}) less than a day ago, and when no
     * claim at the emitted level exists for its current cycle and assignee.
     */
    private static final String CANDIDATES = """
            SELECT r.id, r.number, r.subject, r.status, r.assignee_id, r.last_activity_at
            FROM support_request r
            WHERE (
                  (r.status = 'new' AND (
                        (r.last_activity_at < :newCut AND r.last_activity_at >= :newStaleCut)
                     OR (r.last_activity_at >= :newCut AND r.last_activity_at < :newWarnCut
                         AND r.last_activity_at >= :newWarnStaleCut)))
               OR (r.status = 'in_progress' AND (
                        (r.last_activity_at < :inProgressCut AND r.last_activity_at >= :inProgressStaleCut)
                     OR (r.last_activity_at >= :inProgressCut AND r.last_activity_at < :inProgressWarnCut
                         AND r.last_activity_at >= :inProgressWarnStaleCut))))
              AND NOT EXISTS (
                  SELECT 1 FROM support_sla_alert a
                  WHERE a.request_id = r.id AND a.cycle_at = r.last_activity_at
                    AND a.assignee_id IS NOT DISTINCT FROM r.assignee_id
                    AND a.level = CASE
                        WHEN (r.status = 'new' AND r.last_activity_at < :newCut)
                          OR (r.status = 'in_progress' AND r.last_activity_at < :inProgressCut)
                        THEN 'overdue' ELSE 'due_soon' END)
            ORDER BY r.last_activity_at ASC, r.number ASC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SlaAlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One request as the sweep reads it; {@code lastActivityAt} is the cycle the claim is keyed on. */
    record SlaRow(UUID id, long number, String subject, RequestStatus status, UUID assigneeId,
            Instant lastActivityAt) {
    }

    /**
     * The instants a request's last activity is compared with: a request older than {@code newCut} is overdue, one
     * between it and {@code newWarnCut} is due soon. The stale cuts sit a day further back; a crossing before them
     * is a backlog, not an alert.
     */
    record SlaCuts(Instant newCut, Instant newWarnCut, Instant inProgressCut, Instant inProgressWarnCut) {

        static SlaCuts at(Instant now, SlaThresholds thresholds) {
            return new SlaCuts(now.minus(thresholds.newLimit()), now.minus(thresholds.newWarnCut()),
                    now.minus(thresholds.inProgressLimit()), now.minus(thresholds.inProgressWarnCut()));
        }

        Instant newStaleCut() {
            return newCut.minus(SlaAlerts.FRESH_WINDOW);
        }

        Instant newWarnStaleCut() {
            return newWarnCut.minus(SlaAlerts.FRESH_WINDOW);
        }

        Instant inProgressStaleCut() {
            return inProgressCut.minus(SlaAlerts.FRESH_WINDOW);
        }

        Instant inProgressWarnStaleCut() {
            return inProgressWarnCut.minus(SlaAlerts.FRESH_WINDOW);
        }
    }

    /** At most {@code limit} fresh, unalerted crossings, oldest activity first. */
    List<SlaRow> candidates(SlaCuts cuts, int limit) {
        return jdbc.query(CANDIDATES, new MapSqlParameterSource()
                .addValue("newCut", Timestamp.from(cuts.newCut()))
                .addValue("newStaleCut", Timestamp.from(cuts.newStaleCut()))
                .addValue("newWarnCut", Timestamp.from(cuts.newWarnCut()))
                .addValue("newWarnStaleCut", Timestamp.from(cuts.newWarnStaleCut()))
                .addValue("inProgressCut", Timestamp.from(cuts.inProgressCut()))
                .addValue("inProgressStaleCut", Timestamp.from(cuts.inProgressStaleCut()))
                .addValue("inProgressWarnCut", Timestamp.from(cuts.inProgressWarnCut()))
                .addValue("inProgressWarnStaleCut", Timestamp.from(cuts.inProgressWarnStaleCut()))
                .addValue("limit", limit), ROW);
    }

    /** The levels of {@code levels} this call newly claimed; one already held by an earlier alert is left out. */
    List<SlaLevel> claim(UUID requestId, List<SlaLevel> levels, Instant cycleAt, UUID assigneeId) {
        List<SlaLevel> claimed = new ArrayList<>();
        for (SlaLevel level : levels) {
            List<String> inserted = jdbc.query("INSERT INTO support_sla_alert (request_id, level, cycle_at, "
                    + "assignee_id) VALUES (:request, :level, :cycle, :assignee) "
                    + "ON CONFLICT ON CONSTRAINT uq_support_sla_alert DO NOTHING RETURNING level",
                    new MapSqlParameterSource().addValue("request", requestId).addValue("level", level.value())
                            .addValue("cycle", Timestamp.from(cycleAt)).addValue("assignee", assigneeId),
                    (rs, n) -> rs.getString("level"));
            if (!inserted.isEmpty()) {
                claimed.add(level);
            }
        }
        return claimed;
    }

    /** Locks the request row and returns it when its status, assignee and last activity are still the planned ones. */
    Optional<SlaRow> lockUnchanged(SlaRow planned) {
        return jdbc.query("SELECT id, number, subject, status, assignee_id, last_activity_at FROM support_request "
                + "WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", planned.id()), ROW).stream()
                .findFirst()
                .filter(current -> current.status() == planned.status()
                        && Objects.equals(current.assigneeId(), planned.assigneeId())
                        && current.lastActivityAt().equals(planned.lastActivityAt()));
    }

    int pruneBefore(Instant cut) {
        return jdbc.update("DELETE FROM support_sla_alert WHERE created_at < :cut",
                new MapSqlParameterSource("cut", Timestamp.from(cut)));
    }
}
