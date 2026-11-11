package com.flowcortex.jobs.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The durable schedule, one row per job. Every write is a conditional {@code UPDATE} on the job's row, so
 * the database arbitrates between instances: one claim wins, and a finish from a claim that was taken over
 * changes nothing.
 */
@Repository
public class ScheduledJobRepository {

    /** The recorded result of a run. */
    public enum Outcome {
        SUCCEEDED("succeeded"),
        FAILED("failed");

        private final String column;

        Outcome(String column) {
            this.column = column;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    ScheduledJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the job's row due at {@code now}, or updates the interval of an existing one and pulls a next
     * run that lies beyond {@code now + interval} forward to it. A lease already on the row is left alone.
     */
    public void register(String name, Duration interval, Instant now) {
        jdbc.update("INSERT INTO scheduled_job (name, interval_ms, next_run_at, created_at, updated_at) "
                + "VALUES (:name, :interval, :now, :now, :now) "
                + "ON CONFLICT (name) DO UPDATE SET interval_ms = EXCLUDED.interval_ms, "
                + "next_run_at = LEAST(scheduled_job.next_run_at, CAST(:now AS timestamptz) "
                + "+ EXCLUDED.interval_ms * interval '1 millisecond'), updated_at = :now",
                new MapSqlParameterSource("name", name).addValue("interval", interval.toMillis())
                        .addValue("now", Timestamp.from(now)));
    }

    /** @return true when this caller now holds the job: it was due and no unexpired lease was on it */
    public boolean claim(String name, UUID token, String owner, Instant now, Duration lease) {
        return jdbc.update("UPDATE scheduled_job SET lease_token = :token, lease_owner = :owner, "
                + "lease_until = CAST(:now AS timestamptz) + CAST(:leaseMs AS bigint) * interval '1 millisecond', "
                + "last_started_at = :now, updated_at = :now "
                + "WHERE name = :name AND next_run_at <= :now AND (lease_until IS NULL OR lease_until <= :now)",
                new MapSqlParameterSource("name", name).addValue("token", token).addValue("owner", owner)
                        .addValue("leaseMs", lease.toMillis()).addValue("now", Timestamp.from(now))) == 1;
    }

    /** Gives up a claim without touching the next run, so the job is due at once for any instance. */
    public boolean release(String name, UUID token, Instant now) {
        return jdbc.update("UPDATE scheduled_job SET lease_token = NULL, lease_owner = NULL, lease_until = NULL, "
                + "updated_at = :now WHERE name = :name AND lease_token = :token",
                new MapSqlParameterSource("name", name).addValue("token", token)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /**
     * Records the run and sets the next one an interval after {@code end}.
     *
     * @param error the exception class simple name, or null
     * @return false when the claim was released or taken over, in which case nothing changed
     */
    public boolean finish(String name, UUID token, Instant end, Outcome outcome, String error) {
        return jdbc.update("UPDATE scheduled_job SET lease_token = NULL, lease_owner = NULL, lease_until = NULL, "
                + "last_finished_at = :end, last_outcome = :outcome, last_error = :error, "
                + "next_run_at = CAST(:end AS timestamptz) + interval_ms * interval '1 millisecond', "
                + "updated_at = :end WHERE name = :name AND lease_token = :token",
                new MapSqlParameterSource("name", name).addValue("token", token)
                        .addValue("end", Timestamp.from(end)).addValue("outcome", outcome.column)
                        .addValue("error", error)) == 1;
    }
}
