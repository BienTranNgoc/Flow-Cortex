package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.BatchRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link BatchRun}. Every status change is one conditional update on the
 * status it may leave and returns the rows it changed, so a write that lost a race returns {@code 0}.
 * Writes done by the dispatcher carry its lease token; a member's carry the owner.
 */
public interface BatchRunRepository extends JpaRepository<BatchRun, UUID> {

    /** A batch with its workflow's name, read in the same statement. */
    record BatchWithWorkflowName(BatchRun batch, String workflowName) {
    }

    /** The owner's batch; another owner's or an unknown id is empty, which callers answer with 404. */
    Optional<BatchRun> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** The owner's batches, newest first, optionally of one status; a {@code null} {@code status} matches all. */
    @Query(value = "SELECT new com.flowcortex.workflow.infrastructure.BatchRunRepository$BatchWithWorkflowName(b, w.name) "
            + "FROM BatchRun b JOIN Workflow w ON w.id = b.workflowId "
            + "WHERE b.ownerId = :ownerId AND (:status IS NULL OR b.status = :status) "
            + "ORDER BY b.createdAt DESC, b.id DESC",
            countQuery = "SELECT count(b) FROM BatchRun b WHERE b.ownerId = :ownerId "
                    + "AND (:status IS NULL OR b.status = :status)")
    Page<BatchWithWorkflowName> findPageByOwner(@Param("ownerId") UUID ownerId, @Param("status") String status,
            Pageable page);

    /** The owner's batches that are {@code running} or {@code paused}: the ones the unfinished limit counts. */
    @Query(value = "SELECT count(*) FROM batch_run WHERE owner_id = :ownerId AND status IN ('running', 'paused')",
            nativeQuery = true)
    long countUnfinishedByOwner(@Param("ownerId") UUID ownerId);

    /** Whether the owner has a batch whose stored status is one of {@code statuses}. */
    boolean existsByOwnerIdAndStatusIn(UUID ownerId, Collection<String> statuses);

    /** Ids of batches with no {@code finished_at}, least recently dispatched first; never-dispatched ones lead. */
    @Query(value = "SELECT id FROM batch_run WHERE finished_at IS NULL "
            + "ORDER BY dispatched_at NULLS FIRST, created_at", nativeQuery = true)
    List<UUID> findOpenForDispatch(Pageable page);

    /**
     * Takes the dispatch lease for {@code token} until {@code until}; {@code 0} when another holder's lease
     * has not expired by {@code now} or the batch is finished. Stamps {@code dispatched_at}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET lease_token = :token, lease_until = :until, dispatched_at = :now "
            + "WHERE id = :id AND finished_at IS NULL AND (lease_until IS NULL OR lease_until < :now)",
            nativeQuery = true)
    int takeLease(@Param("id") UUID id, @Param("token") UUID token, @Param("now") Instant now,
            @Param("until") Instant until);

    /**
     * Whether {@code token} still holds the batch's lease. A write that changed nothing is a lost race when this
     * is true and a lost lease when it is not.
     */
    @Query(value = "SELECT count(*) > 0 FROM batch_run WHERE id = :id AND lease_token = :token", nativeQuery = true)
    boolean holdsLease(@Param("id") UUID id, @Param("token") UUID token);

    /** Clears the lease only while {@code token} still holds it, so a late release never frees another holder's. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET lease_token = NULL, lease_until = NULL "
            + "WHERE id = :id AND lease_token = :token", nativeQuery = true)
    int releaseLease(@Param("id") UUID id, @Param("token") UUID token);

    /** The member's pause: {@code running} -> {@code paused} for the owner's batch. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = 'paused', pause_reason = :reason "
            + "WHERE id = :id AND owner_id = :ownerId AND status = 'running'", nativeQuery = true)
    int pause(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("reason") String reason);

    /** The dispatcher's pause after a refused start: {@code running} -> {@code paused} under its lease. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = 'paused', pause_reason = :reason "
            + "WHERE id = :id AND status = 'running' AND lease_token = :token", nativeQuery = true)
    int pauseUnderLease(@Param("id") UUID id, @Param("token") UUID token, @Param("reason") String reason);

    /** The member's resume, whatever the pause reason: {@code paused} -> {@code running} for the owner's batch. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = 'running', pause_reason = NULL "
            + "WHERE id = :id AND owner_id = :ownerId AND status = 'paused'", nativeQuery = true)
    int resume(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** The dispatcher's resume of a batch it paused as {@code unavailable}, once the cause has cleared. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = 'running', pause_reason = NULL "
            + "WHERE id = :id AND status = 'paused' AND pause_reason = 'unavailable' AND lease_token = :token",
            nativeQuery = true)
    int resumeUnavailable(@Param("id") UUID id, @Param("token") UUID token);

    /** The member's cancel: {@code running|paused} -> {@code cancelled} for the owner's batch. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = 'cancelled', cancel_reason = :reason, pause_reason = NULL "
            + "WHERE id = :id AND owner_id = :ownerId AND status IN ('running', 'paused')", nativeQuery = true)
    int cancel(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("reason") String reason);

    /** The dispatcher's cancel (workflow gone, owner suspended): {@code running|paused} -> {@code cancelled}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = 'cancelled', cancel_reason = :reason, pause_reason = NULL "
            + "WHERE id = :id AND status IN ('running', 'paused') AND lease_token = :token", nativeQuery = true)
    int cancelUnderLease(@Param("id") UUID id, @Param("token") UUID token, @Param("reason") String reason);

    /**
     * Ends a {@code running} or {@code paused} batch as {@code completed} or {@code completed_with_errors}
     * once none of its rows is {@code pending}, {@code starting} or {@code running}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET status = :status, finished_at = :now, pause_reason = NULL "
            + "WHERE id = :id AND status IN ('running', 'paused') AND lease_token = :token "
            + "AND NOT EXISTS (SELECT 1 FROM batch_run_row r WHERE r.batch_id = batch_run.id "
            + "AND r.status IN ('pending', 'starting', 'running'))", nativeQuery = true)
    int finish(@Param("id") UUID id, @Param("status") String status, @Param("now") Instant now,
            @Param("token") UUID token);

    /** Sets {@code finished_at} on a {@code cancelled} batch once none of its rows is open; its status stays. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE batch_run SET finished_at = :now "
            + "WHERE id = :id AND status = 'cancelled' AND finished_at IS NULL AND lease_token = :token "
            + "AND NOT EXISTS (SELECT 1 FROM batch_run_row r WHERE r.batch_id = batch_run.id "
            + "AND r.status IN ('pending', 'starting', 'running'))", nativeQuery = true)
    int finishCancelled(@Param("id") UUID id, @Param("now") Instant now, @Param("token") UUID token);
}
