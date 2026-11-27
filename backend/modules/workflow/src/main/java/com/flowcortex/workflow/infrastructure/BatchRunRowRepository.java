package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.BatchRunRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link BatchRunRow}. Every write is one conditional update on the status it may
 * leave and returns the rows it changed. A write the dispatcher makes also requires its lease token on the
 * batch; {@link #markRunning} and the two run-status syncs record facts about a run that exists, so they do not.
 */
public interface BatchRunRowRepository extends JpaRepository<BatchRunRow, UUID> {

    /** The batch lease holder check every dispatcher write to a row repeats. */
    String HOLDS_LEASE = "EXISTS (SELECT 1 FROM batch_run lb WHERE lb.id = batch_run_row.batch_id "
            + "AND lb.lease_token = :token)";

    /**
     * Claims up to the smallest of {@code budget}, the batch's remaining concurrency, the owner's remaining
     * {@code perMemberCap} and the cluster's remaining {@code clusterCap} {@code pending} rows, lowest index first,
     * as {@code starting}, and returns their ids. Claims nothing unless the batch is {@code running} and held by
     * {@code token}. The advisory lock serialises every claim across batches and instances so the live counts
     * it reads hold; the claim's {@code FOR SHARE} on the batch row makes a pause or cancel that committed first
     * win and one that arrives later wait for the claim.
     */
    @Transactional
    default List<UUID> claimNext(UUID batchId, UUID token, Instant now, int perMemberCap, int clusterCap, int budget) {
        lockClaims();
        return claimCapped(batchId, token, now, perMemberCap, clusterCap, budget);
    }

    /** The transaction-scoped claim lock; only {@link #claimNext} calls it, inside its transaction. */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtext('workflow.batch.claim')) AS text)", nativeQuery = true)
    String lockClaims();

    /** The claim statement of {@link #claimNext}; it counts live rows, so it runs only under the claim lock. */
    @Query(value = "UPDATE batch_run_row SET status = 'starting', claimed_at = :now "
            + "WHERE id IN (SELECT r.id FROM batch_run_row r WHERE r.batch_id = :batchId AND r.status = 'pending' "
            + "ORDER BY r.row_index LIMIT GREATEST(0, LEAST(:budget, "
            + "(SELECT b.concurrency FROM batch_run b WHERE b.id = :batchId) "
            + "- (SELECT count(*) FROM batch_run_row x WHERE x.batch_id = :batchId "
            + "AND x.status IN ('starting', 'running')), "
            + ":perMemberCap - (SELECT count(*) FROM batch_run_row x JOIN batch_run xb ON xb.id = x.batch_id "
            + "WHERE xb.owner_id = (SELECT b.owner_id FROM batch_run b WHERE b.id = :batchId) "
            + "AND x.status IN ('starting', 'running')), "
            + ":clusterCap - (SELECT count(*) FROM batch_run_row x WHERE x.status IN ('starting', 'running'))))) "
            + "AND status = 'pending' "
            + "AND EXISTS (SELECT 1 FROM batch_run b WHERE b.id = :batchId AND b.status = 'running' "
            + "AND b.lease_token = :token FOR SHARE) RETURNING id", nativeQuery = true)
    List<UUID> claimCapped(@Param("batchId") UUID batchId, @Param("token") UUID token, @Param("now") Instant now,
            @Param("perMemberCap") int perMemberCap, @Param("clusterCap") int clusterCap,
            @Param("budget") int budget);

    List<BatchRunRow> findByIdInOrderByRowIndex(Collection<UUID> ids);

    /** Records the run a claimed row started; no lease condition, because the run exists whoever holds it. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row SET status = 'running', run_id = :runId, started_at = :now "
            + "WHERE id = :id AND status = 'starting'", nativeQuery = true)
    int markRunning(@Param("id") UUID id, @Param("runId") UUID runId, @Param("now") Instant now);

    /**
     * Gives a {@code starting} row back after a refused or failed start: {@code pending} while its batch is
     * {@code running} or {@code paused}, else {@code cancelled}, so a row never returns to {@code pending}
     * inside a batch a member has cancelled. {@code countFailure} adds one to {@code start_failures}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row SET "
            + "status = CASE WHEN EXISTS (SELECT 1 FROM batch_run b WHERE b.id = batch_run_row.batch_id "
            + "AND b.status IN ('running', 'paused')) THEN 'pending' ELSE 'cancelled' END, "
            + "finished_at = CASE WHEN EXISTS (SELECT 1 FROM batch_run b WHERE b.id = batch_run_row.batch_id "
            + "AND b.status IN ('running', 'paused')) THEN NULL ELSE CAST(:now AS timestamptz) END, "
            + "start_failures = start_failures + CASE WHEN :countFailure THEN 1 ELSE 0 END, claimed_at = NULL "
            + "WHERE id = :id AND status = 'starting' AND " + HOLDS_LEASE, nativeQuery = true)
    int backToPending(@Param("id") UUID id, @Param("token") UUID token, @Param("countFailure") boolean countFailure,
            @Param("now") Instant now);

    /** {@code starting} -> {@code failed} with the refusal's code and a message cut to its 300 characters. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row SET status = 'failed', error_code = :code, "
            + "error_message = left(:message, 300), finished_at = :now "
            + "WHERE id = :id AND status = 'starting' AND " + HOLDS_LEASE, nativeQuery = true)
    int fail(@Param("id") UUID id, @Param("token") UUID token, @Param("code") String code,
            @Param("message") String message, @Param("now") Instant now);

    /**
     * {@code pending} -> {@code cancelled} for every pending row of a {@code cancelled} batch; a {@code starting}
     * row is left, because its run may be committing. Idempotent, and a no-op for a batch that is not cancelled.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row SET status = 'cancelled', finished_at = :now "
            + "WHERE batch_id = :batchId AND status = 'pending' "
            + "AND EXISTS (SELECT 1 FROM batch_run b WHERE b.id = :batchId AND b.status = 'cancelled')",
            nativeQuery = true)
    int cancelOpen(@Param("batchId") UUID batchId, @Param("now") Instant now);

    /** {@code starting} rows claimed before {@code olderThan}: claims whose holder is gone, oldest index first. */
    @Query("SELECT r FROM BatchRunRow r WHERE r.batchId = :batchId "
            + "AND r.status = com.flowcortex.workflow.domain.BatchRunRow.STARTING AND r.claimedAt < :olderThan "
            + "ORDER BY r.rowIndex")
    List<BatchRunRow> findAbandonedStarting(@Param("batchId") UUID batchId, @Param("olderThan") Instant olderThan);

    /**
     * Moves each {@code running} row whose run reached a final status to {@code completed}, {@code failed}
     * ({@code error}) or {@code cancelled}. The run's {@code deleted_at} is ignored: a removed run still ended.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row r SET "
            + "status = CASE w.status WHEN 'completed' THEN 'completed' WHEN 'error' THEN 'failed' "
            + "ELSE 'cancelled' END, "
            + "error_code = CASE WHEN w.status = 'error' THEN 'runError' END, "
            + "error_message = CASE WHEN w.status = 'error' THEN left(w.error_message, 300) END, "
            + "finished_at = :now FROM workflow_run w "
            + "WHERE r.batch_id = :batchId AND r.status = 'running' AND w.id = r.run_id "
            + "AND w.status IN ('completed', 'error', 'cancelled')", nativeQuery = true)
    int syncFromRuns(@Param("batchId") UUID batchId, @Param("now") Instant now);

    /**
     * Moves each {@code failed} row whose run was later re-opened and has since {@code completed} or been
     * {@code cancelled} to that status, clearing the error; a row whose run is live or still {@code error} stays.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row r SET status = w.status, error_code = NULL, error_message = NULL "
            + "FROM workflow_run w WHERE r.batch_id = :batchId AND r.status = 'failed' AND w.id = r.run_id "
            + "AND w.status IN ('completed', 'cancelled')", nativeQuery = true)
    int resyncFailedFromRuns(@Param("batchId") UUID batchId);

    /** Records that the run of a {@code running} row was asked to stop, so the next pass does not ask again. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run_row SET stop_requested = true "
            + "WHERE id = :id AND status = 'running' AND stop_requested = false AND " + HOLDS_LEASE,
            nativeQuery = true)
    int markStopRequested(@Param("id") UUID id, @Param("token") UUID token);

    /** Rows of the batch whose run is live: the ones counted against its concurrency. */
    @Query("SELECT count(r) FROM BatchRunRow r WHERE r.batchId = :batchId "
            + "AND r.status IN (com.flowcortex.workflow.domain.BatchRunRow.STARTING, "
            + "com.flowcortex.workflow.domain.BatchRunRow.RUNNING)")
    long countLive(@Param("batchId") UUID batchId);

    /**
     * Row counts per batch for a page of batches in one grouped read; a {@code starting} row counts as
     * {@code pending}. A batch with no rows has no entry.
     */
    @Query("SELECT new com.flowcortex.workflow.infrastructure.BatchRowCounts(r.batchId, "
            + "count(CASE WHEN r.status IN ('pending', 'starting') THEN 1 END), "
            + "count(CASE WHEN r.status = 'running' THEN 1 END), "
            + "count(CASE WHEN r.status = 'completed' THEN 1 END), "
            + "count(CASE WHEN r.status = 'failed' THEN 1 END), "
            + "count(CASE WHEN r.status = 'cancelled' THEN 1 END)) "
            + "FROM BatchRunRow r WHERE r.batchId IN :batchIds GROUP BY r.batchId")
    List<BatchRowCounts> countsFor(@Param("batchIds") Collection<UUID> batchIds);

    /**
     * The batch's rows in row order, optionally of one member-facing status; a {@code null} {@code status}
     * matches all and {@code pending} also matches a {@code starting} row.
     */
    @Query(value = "SELECT r FROM BatchRunRow r WHERE r.batchId = :batchId "
            + "AND (:status IS NULL OR r.status = :status "
            + "OR (:status = 'pending' AND r.status = com.flowcortex.workflow.domain.BatchRunRow.STARTING)) "
            + "ORDER BY r.rowIndex",
            countQuery = "SELECT count(r) FROM BatchRunRow r WHERE r.batchId = :batchId "
                    + "AND (:status IS NULL OR r.status = :status "
                    + "OR (:status = 'pending' AND r.status = com.flowcortex.workflow.domain.BatchRunRow.STARTING))")
    Page<BatchRunRow> findPage(@Param("batchId") UUID batchId, @Param("status") String status, Pageable page);

    /** The batch's {@code failed} rows in row order. */
    @Query("SELECT r FROM BatchRunRow r WHERE r.batchId = :batchId "
            + "AND r.status = com.flowcortex.workflow.domain.BatchRunRow.FAILED ORDER BY r.rowIndex")
    List<BatchRunRow> findFailedOrdered(@Param("batchId") UUID batchId);

    /** {@code running} rows whose run has not been asked to stop yet, in row order. */
    @Query("SELECT r FROM BatchRunRow r WHERE r.batchId = :batchId "
            + "AND r.status = com.flowcortex.workflow.domain.BatchRunRow.RUNNING AND r.runId IS NOT NULL "
            + "AND r.stopRequested = false ORDER BY r.rowIndex")
    List<BatchRunRow> findLiveNotStopRequested(@Param("batchId") UUID batchId);
}
