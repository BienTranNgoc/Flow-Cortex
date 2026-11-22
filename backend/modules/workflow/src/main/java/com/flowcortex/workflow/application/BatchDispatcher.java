package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.application.BatchDispatchConfig.BatchDispatchExecutor;
import com.flowcortex.workflow.domain.BatchRun;
import com.flowcortex.workflow.domain.BatchRunRow;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.BatchRowCounts;
import com.flowcortex.workflow.infrastructure.BatchRunRepository;
import com.flowcortex.workflow.infrastructure.BatchRunRowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Starts the rows of a batch a few at a time and ends the batch. One pass per batch runs on the dispatch pool with
 * no ambient transaction: every step is its own short transaction, so a refused start or run stop rolls back only
 * itself. A pass holds the batch's lease under a token of its own, and every write it makes to the batch or its
 * rows is conditional on that token; a write that finds the lease gone ends the pass.
 */
@Component
class BatchDispatcher {

    /** Rows one pass starts for one batch, so a large batch yields the pool to the others. */
    static final int PASS_BUDGET = 10;

    private static final Logger LOG = LoggerFactory.getLogger(BatchDispatcher.class);

    private final BatchRunRepository batches;
    private final BatchRunRowRepository rows;
    private final WorkflowRunRepository runs;
    private final WorkflowRunService runService;
    private final RunCancellation cancellation;
    private final RunStartPreflight preflight;
    private final UserDirectory users;
    private final BatchDispatchExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    private final Duration lease;
    private final int perMemberCap;
    private final int clusterCap;
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    BatchDispatcher(BatchRunRepository batches, BatchRunRowRepository rows, WorkflowRunRepository runs,
            WorkflowRunService runService, RunCancellation cancellation, RunStartPreflight preflight,
            UserDirectory users, BatchDispatchExecutor executor, Clock clock,
            @Value("${flowcortex.workflow.batch.dispatch-enabled:true}") boolean enabled,
            @Value("${flowcortex.workflow.batch.lease:PT2M}") Duration lease,
            @Value("${flowcortex.workflow.batch.max-live-rows-per-member:5}") int perMemberCap,
            @Value("${flowcortex.workflow.batch.max-live-rows:8}") int clusterCap) {
        this.batches = batches;
        this.rows = rows;
        this.runs = runs;
        this.runService = runService;
        this.cancellation = cancellation;
        this.preflight = preflight;
        this.users = users;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.lease = lease;
        this.perMemberCap = perMemberCap;
        this.clusterCap = clusterCap;
    }

    /** A batch changed in a way its next pass should see now; the tick is the safety net if this one is lost. */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(BatchDispatchRequested event) {
        request(event.batchId());
    }

    /**
     * Hands the batch to the dispatch pool. A batch already queued or running here is skipped, and so is one the
     * full pool rejects; the next tick offers it again.
     */
    void request(UUID batchId) {
        if (!enabled || !queued.add(batchId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    dispatch(batchId);
                } finally {
                    queued.remove(batchId);
                }
            });
        } catch (TaskRejectedException e) {
            queued.remove(batchId);
            LOG.debug("batch.dispatch skipped, pool full: batchId={}", batchId);
        }
    }

    /** One pass over one batch; returns at once when another holder has its lease. */
    void dispatch(UUID batchId) {
        long begun = System.nanoTime();
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        if (batches.takeLease(batchId, token, now, now.plus(lease)) == 0) {
            return;
        }
        Pass pass = new Pass(batchId, token);
        try {
            pass.run();
        } catch (LeaseLost lost) {
            LOG.warn("batch.lease_lost batchId={}", batchId);
        } catch (RuntimeException e) {
            LOG.error("batch.dispatch failed: batchId={}", batchId, e);
        } finally {
            release(batchId, token);
            long passMs = Duration.ofNanos(System.nanoTime() - begun).toMillis();
            String line = "batch.dispatch batchId={} started={} synced={} pass_ms={}";
            if (pass.acted()) {
                LOG.info(line, batchId, pass.started, pass.synced, passMs);
            } else {
                LOG.debug(line, batchId, pass.started, pass.synced, passMs);
            }
        }
    }

    private void release(UUID batchId, UUID token) {
        try {
            batches.releaseLease(batchId, token);
        } catch (RuntimeException e) {
            LOG.error("batch.dispatch could not release its lease: batchId={}", batchId, e);
        }
    }

    private static String requestKey(UUID batchId, BatchRunRow row) {
        return "batch:" + batchId + ":" + row.getRowIndex();
    }

    /** The write found the batch's lease held by another token: nothing further this pass may be written. */
    private static final class LeaseLost extends RuntimeException {

        LeaseLost() {
            super(null, null, false, false);
        }
    }

    /** The state of one dispatch pass over one batch, under one lease token. */
    private final class Pass {

        private final UUID batchId;
        private final UUID token;
        private int started;
        private int synced;
        private int changes;

        Pass(UUID batchId, UUID token) {
            this.batchId = batchId;
            this.token = token;
        }

        boolean acted() {
            return started > 0 || synced > 0 || changes > 0;
        }

        void run() {
            synced = rows.syncFromRuns(batchId, clock.instant());
            BatchRun batch = load();
            if (batch.getFinishedAt() != null) {
                return;
            }
            if (isOpen(batch) && !ownerActive(batch.getOwnerId())) {
                applied(batches.cancelUnderLease(batchId, token, BatchStatus.CANCEL_OWNER_INACTIVE));
                batch = load();
            }
            if (isUnavailablePause(batch) && unavailableCleared(batch)) {
                applied(batches.resumeUnavailable(batchId, token));
                batch = load();
            }
            resolveAbandonedClaims(batch);
            if (BatchRun.RUNNING.equals(batch.getStatus())) {
                startRows(batch);
                batch = load();
            }
            if (BatchRun.CANCELLED.equals(batch.getStatus())) {
                settleCancelled(batch);
            } else {
                finishIfDone(batch);
            }
        }

        private BatchRun load() {
            return batches.findById(batchId).orElseThrow(() -> new IllegalStateException("Batch gone: " + batchId));
        }

        private boolean isOpen(BatchRun batch) {
            return BatchRun.RUNNING.equals(batch.getStatus()) || BatchRun.PAUSED.equals(batch.getStatus());
        }

        private boolean isUnavailablePause(BatchRun batch) {
            return BatchRun.PAUSED.equals(batch.getStatus())
                    && BatchStatus.PAUSE_UNAVAILABLE.equals(batch.getPauseReason());
        }

        private boolean ownerActive(UUID ownerId) {
            try {
                return users.findById(ownerId).isPresent();
            } catch (AccessDeniedException suspended) {
                return false;
            }
        }

        /**
         * Whether the start checks pass again. Only a definite 503 keeps the batch paused; any other refusal
         * resumes it, and the next row start classifies that refusal. An unexpected error leaves it paused.
         */
        private boolean unavailableCleared(BatchRun batch) {
            try {
                Workflow workflow = preflight.resolve(batch.getOwnerId(), batch.getWorkflowId());
                preflight.checkSteps(batch.getOwnerId(), workflow, batch.isAcknowledgeCost());
                return true;
            } catch (RuntimeException refusal) {
                BatchRefusals.Outcome outcome = BatchRefusals.classify(refusal, 0);
                if (outcome.action() == BatchRefusals.Action.RETRY_ROW) {
                    LOG.warn("batch.dispatch could not re-check an unavailable batch: batchId={}", batchId, refusal);
                    return false;
                }
                return !(outcome.action() == BatchRefusals.Action.PAUSE
                        && BatchStatus.PAUSE_UNAVAILABLE.equals(outcome.reason()));
            }
        }

        /**
         * A claim whose holder is gone: its run, if the start committed one, is recorded by its request key;
         * without one the row goes back to pending (or cancelled, in a cancelled batch).
         */
        private void resolveAbandonedClaims(BatchRun batch) {
            Instant cutoff = clock.instant().minus(lease.multipliedBy(2));
            for (BatchRunRow row : rows.findAbandonedStarting(batchId, cutoff)) {
                resolveByKey(batch, row);
            }
        }

        private void resolveByKey(BatchRun batch, BatchRunRow row) {
            Optional<WorkflowRun> run = runs.findByOwnerIdAndRequestKey(batch.getOwnerId(), requestKey(batchId, row));
            if (run.isPresent()) {
                rows.markRunning(row.getId(), run.get().getId(), clock.instant());
            } else {
                applied(rows.backToPending(row.getId(), token, false, clock.instant()));
            }
        }

        private void startRows(BatchRun batch) {
            List<UUID> claimed = rows.claimNext(batchId, token, clock.instant(), perMemberCap, clusterCap,
                    PASS_BUDGET);
            if (claimed.isEmpty()) {
                if (!batches.holdsLease(batchId, token)) {
                    throw new LeaseLost();
                }
                return;
            }
            List<BatchRunRow> claimedRows = rows.findByIdInOrderByRowIndex(claimed);
            for (int index = 0; index < claimedRows.size(); index++) {
                BatchRunRow row = claimedRows.get(index);
                try {
                    RunView run = runService.startRun(batch.getOwnerId(), batch.getWorkflowId(), row.getInput(), null,
                            null, null, batch.isAcknowledgeCost(), requestKey(batchId, row));
                    rows.markRunning(row.getId(), run.id(), clock.instant());
                    started++;
                } catch (RuntimeException refusal) {
                    if (!handleRefusal(batch, row, refusal, claimedRows.subList(index + 1, claimedRows.size()))) {
                        return;
                    }
                }
            }
        }

        /** Applies the refusal's outcome; false when the rest of the claimed rows must not be started. */
        private boolean handleRefusal(BatchRun batch, BatchRunRow row, RuntimeException refusal,
                List<BatchRunRow> unattempted) {
            BatchRefusals.Outcome outcome = BatchRefusals.classify(refusal, row.getStartFailures());
            LOG.info("batch.dispatch start refused: batchId={} rowIndex={} outcome={} reason={}", batchId,
                    row.getRowIndex(), outcome.action(), outcome.reason() != null ? outcome.reason()
                            : outcome.errorCode());
            switch (outcome.action()) {
                case PAUSE -> {
                    giveBack(row, false);
                    giveBackAll(unattempted);
                    applied(batches.pauseUnderLease(batchId, token, outcome.reason()));
                    return false;
                }
                case CANCEL_BATCH -> {
                    applied(batches.cancelUnderLease(batchId, token, outcome.reason()));
                    resolveByKey(batch, row);
                    giveBackAll(unattempted);
                    return false;
                }
                case FAIL_ROW -> {
                    applied(rows.fail(row.getId(), token, outcome.errorCode(), outcome.errorMessage(),
                            clock.instant()));
                    return true;
                }
                case RETRY_ROW -> {
                    LOG.warn("batch.dispatch unexpected start error: batchId={} rowIndex={}", batchId,
                            row.getRowIndex(), refusal);
                    giveBack(row, outcome.countsAsStartFailure());
                    giveBackAll(unattempted);
                    return false;
                }
                default -> throw new IllegalStateException("Unhandled outcome " + outcome.action());
            }
        }

        private void giveBack(BatchRunRow row, boolean countFailure) {
            applied(rows.backToPending(row.getId(), token, countFailure, clock.instant()));
        }

        private void giveBackAll(List<BatchRunRow> unattempted) {
            unattempted.forEach(row -> giveBack(row, false));
        }

        /**
         * A cancelled batch: its pending rows are cancelled, each live run is asked once to stop (the only place
         * a batch stops a run), and the batch gets its finish time once no row is open.
         */
        private void settleCancelled(BatchRun batch) {
            changes += rows.cancelOpen(batchId, clock.instant());
            for (BatchRunRow row : rows.findLiveNotStopRequested(batchId)) {
                requestStop(batch, row);
            }
            applied(batches.finishCancelled(batchId, clock.instant(), token));
        }

        private void requestStop(BatchRun batch, BatchRunRow row) {
            try {
                cancellation.cancelRun(batch.getOwnerId(), row.getRunId());
            } catch (ConflictException | NotFoundException settled) {
                // The run already ended or is gone; the next sync records the row's final status.
            } catch (LocalizedStatusException refused) {
                if (!refused.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
                    LOG.error("batch.dispatch could not stop a run: batchId={} rowIndex={}", batchId,
                            row.getRowIndex(), refused);
                    return;
                }
            } catch (RuntimeException e) {
                LOG.error("batch.dispatch could not stop a run: batchId={} rowIndex={}", batchId, row.getRowIndex(),
                        e);
                return;
            }
            applied(rows.markStopRequested(row.getId(), token));
        }

        private void finishIfDone(BatchRun batch) {
            if (!isOpen(batch)) {
                return;
            }
            BatchRowCounts counts = rows.countsFor(List.of(batchId)).stream().findFirst()
                    .orElse(new BatchRowCounts(batchId, 0, 0, 0, 0, 0));
            if (counts.pending() > 0 || counts.running() > 0) {
                return;
            }
            BatchStatus finished = BatchStatus.of(batch.getStatus()).finished((int) counts.failed());
            applied(batches.finish(batchId, finished.value(), clock.instant(), token));
        }

        /** Counts a write that changed something; one that changed nothing is checked for a lost lease. */
        private void applied(int changed) {
            if (changed > 0) {
                changes++;
            } else if (!batches.holdsLease(batchId, token)) {
                throw new LeaseLost();
            }
        }
    }
}
