package com.flowcortex.workflow.application.vendor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.VendorTaskReport;
import com.flowcortex.media.api.provider.VendorLedger;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger of paid asynchronous vendor jobs: what was submitted, who may attach to it, and who
 * consumed it. One row per (vendor, vendor job id); every write goes through that unique key, so a
 * record and a consume may arrive in either order and twice.
 */
@Component
public class VendorJobLedger {

    private static final Logger LOG = LoggerFactory.getLogger(VendorJobLedger.class);
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    /** The most candidates one dispatch may carry; the job contract caps the list at the same size. */
    static final int MAX_CANDIDATES = 5;

    /** The step that currently has a claim on a row: the one holding it, else the one that submitted it. */
    private static final String HOLDER = "COALESCE(v.held_by_step_id, v.requested_by_step_id)";
    private static final String OWNER_OF_RUN = "(SELECT owner_id FROM workflow_run WHERE id = :run)";

    private static final String CLAIM_SQL = """
            WITH picked AS (
                SELECT v.id, CASE WHEN %1$s = :step THEN 0 ELSE 1 END AS own
                FROM vendor_task v
                WHERE v.owner_id = %2$s
                  AND v.capability = :capability
                  AND v.request_key = CAST(:requestKey AS char(64))
                  AND v.consumed_at IS NULL
                  AND v.status IN ('running', 'succeeded')
                  AND v.fingerprint IS NOT NULL
                  AND (%1$s IS NULL OR %1$s = :step
                       OR NOT EXISTS (SELECT 1 FROM run_step s WHERE s.id = %1$s AND s.status = 'running'))
                ORDER BY own, v.created_at, v.id
                LIMIT %3$d
                FOR UPDATE OF v SKIP LOCKED)
            UPDATE vendor_task t SET held_by_step_id = :step, updated_at = :now
            FROM picked p
            WHERE t.id = p.id
            RETURNING t.id, t.vendor, t.model, t.fingerprint, t.vendor_job_id, t.handle::text AS handle,
                      t.created_at, p.own
            """.formatted(HOLDER, OWNER_OF_RUN, MAX_CANDIDATES);

    private static final String RETIRE_OWN_SLOT_SQL = """
            UPDATE vendor_task v SET status = 'gone', updated_at = :now
            WHERE v.owner_id = %s
              AND v.vendor = :vendor
              AND v.fingerprint = CAST(:fingerprint AS char(64))
              AND v.consumed_at IS NULL
              AND v.status IN ('running', 'succeeded')
              AND (v.requested_by_step_id = :step OR v.held_by_step_id = :step)
              AND v.vendor_job_id <> :vendorJobId
            """.formatted(OWNER_OF_RUN);

    private static final String INSERT_SUBMIT_SQL = """
            INSERT INTO vendor_task (id, owner_id, capability, request_key, fingerprint, vendor, model,
                                     vendor_job_id, handle, status, vendor_created_at, requested_by_step_id,
                                     created_at, updated_at)
            SELECT :id, r.owner_id, s.type, CAST(:requestKey AS char(64)), CAST(:fingerprint AS char(64)),
                   :vendor, :model, :vendorJobId, CAST(:handle AS jsonb), 'running', :vendorCreatedAt, s.id,
                   :now, :now
            FROM run_step s JOIN workflow_run r ON r.id = s.run_id
            WHERE s.id = :step AND r.id = :run
            ON CONFLICT (vendor, vendor_job_id) DO NOTHING
            """;

    private static final String MARK_SQL = """
            UPDATE vendor_task SET status = :status, updated_at = :now
            WHERE id = :id AND consumed_at IS NULL AND owner_id = %s
            """.formatted(OWNER_OF_RUN);

    private static final String MARK_BY_JOB_SQL = """
            UPDATE vendor_task SET status = :status, updated_at = :now
            WHERE vendor = :vendor AND vendor_job_id = :vendorJobId AND consumed_at IS NULL
              AND owner_id = %s
            """.formatted(OWNER_OF_RUN);

    private static final String CONSUME_UPSERT_SQL = """
            INSERT INTO vendor_task (id, owner_id, capability, request_key, fingerprint, vendor, model,
                                     vendor_job_id, handle, status, vendor_created_at, requested_by_step_id,
                                     consumed_by_step_id, consumed_at, created_at, updated_at)
            SELECT :id, r.owner_id, :capability, CAST(:requestKey AS char(64)), CAST(:fingerprint AS char(64)),
                   :vendor, :model, :vendorJobId, '{}'::jsonb, 'succeeded', :now, :step, :step, :now, :now, :now
            FROM workflow_run r WHERE r.id = :run
            ON CONFLICT (vendor, vendor_job_id) DO UPDATE
               SET consumed_at = :now, consumed_by_step_id = :step, status = 'succeeded', updated_at = :now
               WHERE vendor_task.consumed_at IS NULL AND vendor_task.owner_id = EXCLUDED.owner_id
            """;

    private static final String CONSUME_EXISTING_SQL = """
            UPDATE vendor_task SET consumed_at = :now, consumed_by_step_id = :step, status = 'succeeded',
                                   updated_at = :now
            WHERE vendor = :vendor AND vendor_job_id = :vendorJobId AND consumed_at IS NULL
              AND owner_id = %s
            """.formatted(OWNER_OF_RUN);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    VendorJobLedger(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Selects up to five attachable rows of the run's owner for this request and claims them for
     * {@code stepId}, this step's own rows first, then the oldest. Runs in the dispatching claim
     * transaction, so the new holder and that holder's {@code running} status commit together.
     * A database error rolls back to a savepoint and yields no candidates: the step still dispatches.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<VendorLedger.Candidate> claimCandidates(UUID runId, UUID stepId, String capability,
            String requestKey) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("run", runId)
                .addValue("step", stepId)
                .addValue("capability", capability)
                .addValue("requestKey", requestKey)
                .addValue("now", timestamp(now()));
        try {
            List<Claimed> claimed = withSavepoint(() -> jdbc.query(CLAIM_SQL, params, (rs, row) -> new Claimed(
                    rs.getInt("own"), rs.getTimestamp("created_at").toInstant(), new VendorLedger.Candidate(
                            rs.getObject("id", UUID.class), rs.getString("vendor"), rs.getString("model"),
                            rs.getString("fingerprint"), rs.getString("vendor_job_id"),
                            handle(rs.getString("handle"))))));
            List<VendorLedger.Candidate> candidates = claimed == null ? List.of() : claimed.stream()
                    .sorted(Comparator.comparingInt(Claimed::own).thenComparing(Claimed::createdAt)
                            .thenComparing(c -> c.candidate().vendorTaskId()))
                    .map(Claimed::candidate)
                    .toList();
            for (VendorLedger.Candidate candidate : candidates) {
                LOG.info("vendor_job outcome=claim run={} step={} ledger={} vendor={} job={}", runId, stepId,
                        candidate.vendorTaskId(), candidate.vendor(), candidate.vendorJobId());
            }
            return candidates;
        } catch (DataAccessException e) {
            LOG.warn("vendor_job outcome=ledger_error op=claim run={} step={}", runId, stepId, e);
            return List.of();
        }
    }

    /**
     * Stores a newly submitted vendor job requested by {@code stepId}, first retiring every other
     * live row of the same owner, vendor and fingerprint that this step requested or holds. Idempotent
     * per (vendor, vendor job id); the step may already be closed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID runId, UUID stepId, VendorTaskReport report) {
        Instant now = now();
        jdbc.update(RETIRE_OWN_SLOT_SQL, new MapSqlParameterSource()
                .addValue("run", runId)
                .addValue("step", stepId)
                .addValue("vendor", report.vendor())
                .addValue("fingerprint", report.fingerprint())
                .addValue("vendorJobId", report.vendorJobId())
                .addValue("now", timestamp(now)));
        UUID ledgerId = UUID.randomUUID();
        int inserted = jdbc.update(INSERT_SUBMIT_SQL, new MapSqlParameterSource()
                .addValue("id", ledgerId)
                .addValue("run", runId)
                .addValue("step", stepId)
                .addValue("requestKey", report.requestKey())
                .addValue("fingerprint", report.fingerprint())
                .addValue("vendor", report.vendor())
                .addValue("model", report.model())
                .addValue("vendorJobId", report.vendorJobId())
                .addValue("handle", json(report.handle()))
                .addValue("vendorCreatedAt", timestamp(report.vendorCreatedAt()))
                .addValue("now", timestamp(now)));
        LOG.info("vendor_job outcome=record run={} step={} ledger={} vendor={} job={} new={}", runId, stepId,
                inserted == 1 ? ledgerId : null, report.vendor(), report.vendorJobId(), inserted == 1);
    }

    /** Applies a worker's verdict to an unconsumed row of the run's owner; a consumed row never reopens. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(UUID runId, UUID stepId, UUID vendorTaskId, VendorTaskReport.Status status) {
        int updated = jdbc.update(MARK_SQL, new MapSqlParameterSource()
                .addValue("id", vendorTaskId)
                .addValue("run", runId)
                .addValue("status", status.value())
                .addValue("now", timestamp(now())));
        LOG.info("vendor_job outcome=mark run={} step={} ledger={} status={} updated={}", runId, stepId, vendorTaskId,
                status.value(), updated);
    }

    /**
     * Marks the row of a vendor job a worker just submitted and saw end, found by its unique key.
     * The row may not exist yet (the record can arrive after the verdict), and a row of another
     * owner is never touched.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markByJob(UUID runId, UUID stepId, String vendor, String vendorJobId, VendorTaskReport.Status status) {
        int updated = jdbc.update(MARK_BY_JOB_SQL, new MapSqlParameterSource()
                .addValue("vendor", vendor)
                .addValue("vendorJobId", vendorJobId)
                .addValue("run", runId)
                .addValue("status", status.value())
                .addValue("now", timestamp(now())));
        LOG.info("vendor_job outcome=mark_by_job run={} step={} vendor={} job={} status={} updated={}", runId, stepId,
                vendor, vendorJobId, status.value(), updated);
    }

    /**
     * Consumes the row of the vendor job a completed result names, once, for {@code stepId}. A result
     * that carries a request key may arrive before the record and creates the consumed row; one
     * without it (an older worker) can only consume a row that already exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(UUID runId, UUID stepId, String capability, Map<String, Object> data) {
        String vendor = text(data, "provider");
        String vendorJobId = text(data, "providerTaskId");
        if (vendor == null || vendorJobId == null) {
            LOG.warn("vendor_job outcome=consume_missing run={} step={} reason=no_vendor_job", runId, stepId);
            return;
        }
        String requestKey = text(data, "requestKey");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("run", runId)
                .addValue("step", stepId)
                .addValue("vendor", vendor)
                .addValue("vendorJobId", vendorJobId)
                .addValue("now", timestamp(now()));
        int updated;
        if (requestKey == null) {
            updated = jdbc.update(CONSUME_EXISTING_SQL, params);
        } else {
            updated = jdbc.update(CONSUME_UPSERT_SQL, params
                    .addValue("id", UUID.randomUUID())
                    .addValue("capability", capability)
                    .addValue("requestKey", requestKey)
                    .addValue("fingerprint", text(data, "fingerprint"))
                    .addValue("model", String.valueOf(data.getOrDefault("model", ""))));
        }
        LOG.info("vendor_job outcome={} run={} step={} vendor={} job={}", updated == 1 ? "consume" : "consume_missing",
                runId, stepId, vendor, vendorJobId);
    }

    /**
     * Runs {@code work} behind a JDBC savepoint on the dispatching transaction's connection, so a
     * failing statement is undone without aborting the surrounding transaction. The JPA transaction
     * manager rejects nested transactions, hence the savepoint is taken on the connection itself.
     */
    private <T> T withSavepoint(Supplier<T> work) {
        return jdbc.getJdbcOperations().execute((ConnectionCallback<T>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try {
                T result = work.get();
                connection.releaseSavepoint(savepoint);
                return result;
            } catch (DataAccessException e) {
                connection.rollback(savepoint);
                throw e;
            }
        });
    }

    private Instant now() {
        return clock.instant();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String text(Map<String, Object> data, String key) {
        return data != null && data.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private String json(Map<String, Object> handle) {
        try {
            return objectMapper.writeValueAsString(handle == null ? Map.of() : handle);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Vendor handle is not serializable", e);
        }
    }

    /** A stored handle that is not a JSON object reads as empty, which no probe accepts as a live job. */
    private Map<String, Object> handle(String stored) {
        try {
            return objectMapper.readValue(stored, OBJECT);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private record Claimed(int own, Instant createdAt, VendorLedger.Candidate candidate) {
    }
}
