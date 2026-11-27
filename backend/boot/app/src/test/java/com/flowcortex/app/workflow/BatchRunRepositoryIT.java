package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.domain.BatchRun;
import com.flowcortex.workflow.domain.BatchRunRow;
import com.flowcortex.workflow.infrastructure.BatchRowCounts;
import com.flowcortex.workflow.infrastructure.BatchRunRepository;
import com.flowcortex.workflow.infrastructure.BatchRunRowRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The conditional writes of {@link BatchRunRepository} and {@link BatchRunRowRepository} against the real schema.
 * The database is shared with every other class of the run, so a test counts only the rows it seeded; the cluster
 * cap is passed as the live rows already present plus the room the test wants.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class BatchRunRepositoryIT extends AbstractWorkflowModuleIT {

    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    private BatchRunRepository batches;

    @Autowired
    private BatchRunRowRepository rows;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void leaseIsTakenByOneCallerUntilItExpires() {
        UserView owner = newUser("lease");
        UUID batch = seedBatch(owner, "running", 1);
        Instant now = Instant.now();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(batches.takeLease(batch, first, now, now.plus(LEASE))).isEqualTo(1);
        assertThat(batches.takeLease(batch, second, now.plusSeconds(1), now.plusSeconds(1).plus(LEASE))).isZero();
        assertThat(leaseToken(batch)).isEqualTo(first);

        Instant afterExpiry = now.plus(LEASE).plusSeconds(1);
        assertThat(batches.takeLease(batch, second, afterExpiry, afterExpiry.plus(LEASE))).isEqualTo(1);
        assertThat(leaseToken(batch)).isEqualTo(second);

        UUID finished = seedBatch(owner, "completed", 1);
        jdbc.update("UPDATE batch_run SET finished_at = now() WHERE id = ?", finished);
        assertThat(batches.takeLease(finished, first, now, now.plus(LEASE))).isZero();
    }

    @Test
    void aReleasedStaleLeaseDoesNotFreeAnotherHoldersLease() {
        UserView owner = newUser("stale");
        UUID batch = seedBatch(owner, "running", 2);
        seedRows(batch, 3);
        Instant now = Instant.now();
        UUID stale = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        batches.takeLease(batch, stale, now.minus(LEASE).minusSeconds(5), now.minusSeconds(5));
        assertThat(batches.takeLease(batch, holder, now, now.plus(LEASE))).isEqualTo(1);

        assertThat(batches.releaseLease(batch, stale)).isZero();
        assertThat(rows.claimNext(batch, stale, now, 5, clusterCap(8), 10)).isEmpty();
        assertThat(leaseToken(batch)).isEqualTo(holder);
        assertThat(rowStatuses(batch)).containsOnly(BatchRunRow.PENDING);
        assertThat(batches.releaseLease(batch, holder)).isEqualTo(1);
        assertThat(leaseToken(batch)).isNull();
    }

    @Test
    void claimTakesOnlyPendingRowsOfARunningBatch() {
        UserView owner = newUser("claim-running");
        UUID running = seedBatch(owner, "running", 10);
        seedRows(running, 2);
        seedRow(running, 2, BatchRunRow.RUNNING, null, null);
        seedRow(running, 3, BatchRunRow.COMPLETED, null, null);
        UUID token = leased(running);

        List<UUID> claimed = rows.claimNext(running, token, Instant.now(), 5, clusterCap(8), 10);

        assertThat(rowIndexes(claimed)).containsExactly(0, 1);
        assertThat(rowStatuses(running)).containsExactly("starting", "starting", "running", "completed");
        assertThat(rows.claimNext(running, token, Instant.now(), 5, clusterCap(8), 10)).isEmpty();
        for (String status : List.of("paused", "cancelled")) {
            UUID notRunning = seedBatch(owner, status, 10);
            seedRows(notRunning, 2);
            assertThat(rows.claimNext(notRunning, leased(notRunning), Instant.now(), 5, clusterCap(8), 10))
                    .as("a %s batch", status).isEmpty();
            assertThat(rowStatuses(notRunning)).containsOnly(BatchRunRow.PENDING);
        }
    }

    @Test
    void claimAfterPauseCommittedClaimsNothing() {
        UserView owner = newUser("claim-pause");
        UUID batch = seedBatch(owner, "running", 10);
        seedRows(batch, 3);
        UUID token = leased(batch);

        assertThat(batches.pause(batch, owner.id(), "member")).isEqualTo(1);

        assertThat(rows.claimNext(batch, token, Instant.now(), 5, clusterCap(8), 10)).isEmpty();
        assertThat(rowStatuses(batch)).containsOnly(BatchRunRow.PENDING);
    }

    @Test
    void aPauseArrivingDuringAClaimWaitsForItAndTheRowStaysClaimed() throws Exception {
        UserView owner = newUser("pause-races-claim");
        UUID batch = seedBatch(owner, "running", 10);
        seedRows(batch, 2);
        UUID token = leased(batch);

        CompletableFuture<Integer> pause = new CompletableFuture<>();
        transactions.executeWithoutResult(status -> {
            assertThat(rows.claimNext(batch, token, Instant.now(), 5, clusterCap(8), 1)).hasSize(1);
            CompletableFuture.supplyAsync(() -> batches.pause(batch, owner.id(), "member")).whenComplete((count, error) -> {
                if (error != null) {
                    pause.completeExceptionally(error);
                } else {
                    pause.complete(count);
                }
            });
            assertThatThrownBy(() -> pause.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        });

        assertThat(pause.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(batchStatus(batch)).isEqualTo("paused");
        assertThat(rowStatuses(batch)).containsExactly("starting", "pending");
        assertThat(rows.claimNext(batch, token, Instant.now(), 5, clusterCap(8), 10)).isEmpty();
    }

    @Test
    void claimIsCappedByTheBatchTheMemberAndTheCluster() {
        UserView first = newUser("cap-first");
        UserView second = newUser("cap-second");
        UserView third = newUser("cap-third");
        UUID firstWide = seedBatch(first, "running", 10);
        UUID firstNarrow = seedBatch(first, "running", 2);
        UUID secondWide = seedBatch(second, "running", 10);
        UUID thirdNarrow = seedBatch(third, "running", 2);
        UUID thirdWide = seedBatch(third, "running", 10);
        for (UUID batch : List.of(firstWide, firstNarrow, secondWide, thirdNarrow, thirdWide)) {
            seedRows(batch, 10);
        }
        UUID firstWideToken = leased(firstWide);
        UUID firstNarrowToken = leased(firstNarrow);
        UUID secondWideToken = leased(secondWide);
        UUID thirdNarrowToken = leased(thirdNarrow);
        UUID thirdWideToken = leased(thirdWide);

        assertThat(rows.claimNext(thirdNarrow, thirdNarrowToken, Instant.now(), 5, clusterCap(100), 10))
                .as("the batch's own concurrency").hasSize(2);
        assertThat(rows.claimNext(thirdWide, thirdWideToken, Instant.now(), 5, clusterCap(100), 1))
                .as("the pass budget").hasSize(1);
        assertThat(rows.claimNext(thirdWide, thirdWideToken, Instant.now(), 5, clusterCap(100), 10))
                .as("the member's remaining room: 5 less the 3 already live").hasSize(2);
        int cluster = clusterCap(8);

        assertThat(rowIndexes(rows.claimNext(firstWide, firstWideToken, Instant.now(), 5, cluster, 10)))
                .as("the member cap, lowest index first").containsExactly(0, 1, 2, 3, 4);
        assertThat(rows.claimNext(firstWide, firstWideToken, Instant.now(), 5, cluster, 10)).isEmpty();
        assertThat(rows.claimNext(firstNarrow, firstNarrowToken, Instant.now(), 5, cluster, 10))
                .as("the same member's other batch").isEmpty();

        assertThat(rows.claimNext(secondWide, secondWideToken, Instant.now(), 5, cluster, 10))
                .as("the cluster cap of 8 less the member's 5 live rows").hasSize(3);
        assertThat(rows.claimNext(secondWide, secondWideToken, Instant.now(), 5, cluster, 10)).isEmpty();
    }

    @Test
    void claimHoldsTheClaimLockUntilItsRowsAreWritten() throws Exception {
        UserView owner = newUser("claim-lock");
        UUID batch = seedBatch(owner, "running", 10);
        seedRows(batch, 2);
        UUID token = leased(batch);
        int cluster = clusterCap(8);

        CompletableFuture<List<UUID>> claim = new CompletableFuture<>();
        boolean lockHeldWhileBlocked = transactions.execute(status -> {
            jdbc.queryForList("SELECT id FROM batch_run_row WHERE batch_id = ? FOR UPDATE", batch);
            CompletableFuture.supplyAsync(() -> rows.claimNext(batch, token, Instant.now(), 5, cluster, 1))
                    .whenComplete((claimed, error) -> {
                        if (error != null) {
                            claim.completeExceptionally(error);
                        } else {
                            claim.complete(claimed);
                        }
                    });
            return awaitClaimLockHeldByAnotherSession();
        });

        assertThat(lockHeldWhileBlocked).as("the claim lock is held while the claim waits for its rows").isTrue();
        assertThat(claim.get(10, TimeUnit.SECONDS)).hasSize(1);
        assertThat(claimLockHeldByAnotherSession()).as("released with the claim's transaction").isFalse();
    }

    @Test
    void rowRecordsItsRunOnlyFromStarting() {
        UserView owner = newUser("mark-running");
        UUID workflow = workflowOf(owner);
        UUID batch = seedBatch(owner, workflow, "running", 5);
        seedRows(batch, 1);
        UUID run = seedRun(workflow, owner.id(), "running", null);
        UUID row = rowIds(batch).get(0);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        assertThat(rows.markRunning(row, run, now)).as("a pending row has no claim to record").isZero();

        List<UUID> claimed = rows.claimNext(batch, leased(batch), now, 5, clusterCap(8), 10);
        assertThat(claimed).containsExactly(row);
        assertThat(rows.markRunning(row, run, now)).isEqualTo(1);
        assertThat(rows.markRunning(row, seedRun(workflow, owner.id(), "running", null), now)).isZero();

        BatchRunRow loaded = rows.findById(row).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BatchRunRow.RUNNING);
        assertThat(loaded.getRunId()).isEqualTo(run);
        assertThat(loaded.getStartedAt()).isEqualTo(now);
    }

    @Test
    void syncMovesRunningRowsToTheirRunsFinalStatus() {
        UserView owner = newUser("sync");
        UUID workflow = workflowOf(owner);
        UUID batch = seedBatch(owner, workflow, "running", 10);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID completed = seedRun(workflow, owner.id(), "completed", now);
        UUID errored = seedRun(workflow, owner.id(), "error", now);
        jdbc.update("UPDATE workflow_run SET error_message = ? WHERE id = ?", "x".repeat(400), errored);
        UUID cancelled = seedRun(workflow, owner.id(), "cancelled", now);
        UUID softDeleted = seedRun(workflow, owner.id(), "completed", now);
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ?", softDeleted);
        UUID live = seedRun(workflow, owner.id(), "running", null);
        UUID waiting = seedRun(workflow, owner.id(), "waiting_approval", null);
        UUID stopping = seedRun(workflow, owner.id(), "cancelling", null);
        List<UUID> runs = List.of(completed, errored, cancelled, softDeleted, live, waiting, stopping);
        for (int i = 0; i < runs.size(); i++) {
            seedRow(batch, i, BatchRunRow.RUNNING, runs.get(i), null);
        }

        assertThat(rows.syncFromRuns(batch, now)).isEqualTo(4);

        assertThat(rowStatuses(batch)).containsExactly("completed", "failed", "cancelled", "completed",
                "running", "running", "running");
        BatchRunRow failed = rows.findByIdInOrderByRowIndex(rowIds(batch)).get(1);
        assertThat(failed.getErrorCode()).isEqualTo("runError");
        assertThat(failed.getErrorMessage()).hasSize(300);
        assertThat(failed.getFinishedAt()).isEqualTo(now);
        assertThat(rows.syncFromRuns(batch, now)).as("nothing left to move").isZero();
    }

    @Test
    void aHardDeletedRunLeavesTheRowWithoutRunId() {
        UserView owner = newUser("hard-delete");
        UUID workflow = workflowOf(owner);
        UUID batch = seedBatch(owner, workflow, "running", 1);
        UUID run = seedRun(workflow, owner.id(), "completed", Instant.now());
        UUID row = seedRow(batch, 0, BatchRunRow.COMPLETED, run, null);

        jdbc.update("DELETE FROM workflow_run WHERE id = ?", run);

        BatchRunRow loaded = rows.findById(row).orElseThrow();
        assertThat(loaded.getRunId()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(BatchRunRow.COMPLETED);
        assertThat(rows.countsFor(List.of(batch))).singleElement().satisfies(counts -> assertThat(counts.completed()).isEqualTo(1));
    }

    @Test
    void finishSetsCompletedWithErrorsOnlyFromRunningOrPaused() {
        UserView owner = newUser("finish");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID running = settledBatch(owner, "running");
        UUID token = leased(running);

        assertThat(batches.finish(running, BatchRun.COMPLETED_WITH_ERRORS, now, UUID.randomUUID()))
                .as("another holder's token").isZero();
        assertThat(batches.finish(running, BatchRun.COMPLETED_WITH_ERRORS, now, token)).isEqualTo(1);
        assertThat(batchStatus(running)).isEqualTo("completed_with_errors");
        assertThat(finishedAt(running)).isEqualTo(Timestamp.from(now));
        assertThat(batches.finish(running, BatchRun.COMPLETED, now, token)).as("already finished").isZero();

        UUID paused = settledBatch(owner, "paused");
        assertThat(batches.finish(paused, BatchRun.COMPLETED, now, leased(paused))).isEqualTo(1);
        for (String status : List.of("cancelled", "completed", "completed_with_errors")) {
            UUID other = settledBatch(owner, status);
            assertThat(batches.finish(other, BatchRun.COMPLETED, now, leased(other))).as(status).isZero();
            assertThat(batchStatus(other)).isEqualTo(status);
        }

        UUID open = seedBatch(owner, "running", 1);
        seedRows(open, 1);
        assertThat(batches.finish(open, BatchRun.COMPLETED, now, leased(open))).as("a pending row is open").isZero();

        UUID cancelled = settledBatch(owner, "cancelled");
        UUID cancelledToken = leased(cancelled);
        assertThat(batches.finishCancelled(cancelled, now, cancelledToken)).isEqualTo(1);
        assertThat(batchStatus(cancelled)).isEqualTo("cancelled");
        assertThat(finishedAt(cancelled)).isEqualTo(Timestamp.from(now));
        assertThat(batches.finishCancelled(cancelled, now, cancelledToken)).isZero();
    }

    @Test
    void cancelTurnsOnlyPendingRowsCancelledInOneTransaction() {
        UserView owner = newUser("cancel");
        UserView other = newUser("cancel-other");
        UUID batch = seedBatch(owner, "running", 5);
        seedRows(batch, 2);
        seedRow(batch, 2, BatchRunRow.STARTING, null, Instant.now());
        seedRow(batch, 3, BatchRunRow.RUNNING, null, null);
        Instant now = Instant.now();

        assertThat(batches.cancel(batch, other.id(), "member")).as("another member's batch").isZero();
        int[] changed = transactions.execute(status ->
                new int[] {batches.cancel(batch, owner.id(), "member"), rows.cancelOpen(batch, now)});

        assertThat(changed).containsExactly(1, 2);
        assertThat(batchStatus(batch)).isEqualTo("cancelled");
        assertThat(rowStatuses(batch)).containsExactly("cancelled", "cancelled", "starting", "running");
        assertThat(batches.cancel(batch, owner.id(), "member")).as("cancelled twice").isZero();
    }

    @Test
    void memberTransitionsAndDispatcherRefusalsEachApplyOnlyToTheStatusTheyRead() {
        UserView owner = newUser("transitions");
        UUID batch = seedBatch(owner, "running", 1);
        UUID token = leased(batch);

        assertThat(batches.resume(batch, owner.id())).as("resume of a running batch").isZero();
        assertThat(batches.pause(batch, owner.id(), "member")).isEqualTo(1);
        assertThat(batches.pause(batch, owner.id(), "member")).isZero();
        assertThat(batches.pauseUnderLease(batch, token, "insufficientCredits")).as("already paused").isZero();
        assertThat(batches.resumeUnavailable(batch, token)).as("a member pause").isZero();
        assertThat(batches.resume(batch, owner.id())).isEqualTo(1);

        assertThat(batches.pauseUnderLease(batch, UUID.randomUUID(), "unavailable")).as("another token").isZero();
        assertThat(batches.pauseUnderLease(batch, token, "unavailable")).isEqualTo(1);
        assertThat(batches.resumeUnavailable(batch, UUID.randomUUID())).isZero();
        assertThat(batches.resumeUnavailable(batch, token)).isEqualTo(1);
        assertThat(batchStatus(batch)).isEqualTo("running");

        assertThat(batches.pauseUnderLease(batch, token, "insufficientCredits")).isEqualTo(1);
        assertThat(batches.cancel(batch, owner.id(), "member")).as("cancel of a credit pause").isEqualTo(1);
        assertThat(batches.pauseUnderLease(batch, token, "insufficientCredits")).as("pause after cancel").isZero();
        assertThat(batches.cancelUnderLease(batch, token, "workflowUnavailable")).as("cancel after cancel").isZero();
        assertThat(batchStatus(batch)).isEqualTo("cancelled");
    }

    @Test
    void aBatchLeavingPausedByAnyExitDropsItsPauseReason() {
        UserView owner = newUser("pause-reason");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        UUID memberCancelled = settledBatch(owner, "running");
        assertThat(batches.pause(memberCancelled, owner.id(), "member")).isEqualTo(1);
        assertThat(batches.cancel(memberCancelled, owner.id(), "member")).isEqualTo(1);
        assertThat(pauseReason(memberCancelled)).as("member cancel of a paused batch").isNull();

        UUID dispatcherCancelled = settledBatch(owner, "running");
        UUID token = leased(dispatcherCancelled);
        assertThat(batches.pauseUnderLease(dispatcherCancelled, token, "insufficientCredits")).isEqualTo(1);
        assertThat(batches.cancelUnderLease(dispatcherCancelled, token, "ownerInactive")).isEqualTo(1);
        assertThat(pauseReason(dispatcherCancelled)).as("dispatcher cancel of a paused batch").isNull();

        UUID finished = settledBatch(owner, "running");
        UUID finishToken = leased(finished);
        assertThat(batches.pauseUnderLease(finished, finishToken, "unavailable")).isEqualTo(1);
        assertThat(batches.finish(finished, BatchRun.COMPLETED_WITH_ERRORS, now, finishToken)).isEqualTo(1);
        assertThat(pauseReason(finished)).as("finish of a paused batch").isNull();
    }

    @Test
    void backToPendingInACancelledBatchWritesCancelled() {
        UserView owner = newUser("back-to-pending");
        UUID batch = seedBatch(owner, "running", 5);
        seedRows(batch, 3);
        UUID token = leased(batch);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<UUID> claimed = rows.claimNext(batch, token, now, 5, clusterCap(8), 10);
        assertThat(claimed).hasSize(3);

        assertThat(rows.backToPending(claimed.get(0), UUID.randomUUID(), true, now)).as("another token").isZero();
        assertThat(rows.backToPending(claimed.get(0), token, true, now)).isEqualTo(1);
        batches.pauseUnderLease(batch, token, "insufficientCredits");
        assertThat(rows.backToPending(claimed.get(1), token, false, now)).as("a paused batch keeps its rows").isEqualTo(1);
        assertThat(rows.findById(claimed.get(0)).orElseThrow().getStartFailures()).isEqualTo((short) 1);
        assertThat(rows.findById(claimed.get(1)).orElseThrow().getStartFailures()).isEqualTo((short) 0);

        batches.cancel(batch, owner.id(), "member");
        assertThat(rows.backToPending(claimed.get(2), token, true, now)).isEqualTo(1);

        BatchRunRow cancelled = rows.findById(claimed.get(2)).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BatchRunRow.CANCELLED);
        assertThat(cancelled.getFinishedAt()).isEqualTo(now);
        assertThat(rows.findById(claimed.get(0)).orElseThrow().getStatus()).isEqualTo(BatchRunRow.PENDING);
        assertThat(rows.backToPending(claimed.get(2), token, true, now)).as("no longer starting").isZero();
    }

    @Test
    void aRefusedStartFailsOnlyAStartingRowWithAMessageCutToThreeHundredCharacters() {
        UserView owner = newUser("fail");
        UUID batch = seedBatch(owner, "running", 5);
        seedRows(batch, 2);
        UUID token = leased(batch);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<UUID> claimed = rows.claimNext(batch, token, now, 5, clusterCap(8), 1);
        UUID pending = rowIds(batch).get(1);

        assertThat(rows.fail(pending, token, "run.invalid", "no", now)).as("a pending row").isZero();
        assertThat(rows.fail(claimed.get(0), UUID.randomUUID(), "run.invalid", "no", now)).isZero();
        assertThat(rows.fail(claimed.get(0), token, "run.invalid", "m".repeat(400), now)).isEqualTo(1);

        BatchRunRow failed = rows.findById(claimed.get(0)).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(BatchRunRow.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("run.invalid");
        assertThat(failed.getErrorMessage()).hasSize(300);
        assertThat(rows.findFailedOrdered(batch)).extracting(BatchRunRow::getId).containsExactly(claimed.get(0));
    }

    @Test
    void cancelOpenIsIdempotent() {
        UserView owner = newUser("cancel-open");
        UUID batch = seedBatch(owner, "running", 1);
        seedRows(batch, 3);
        Instant now = Instant.now();

        assertThat(rows.cancelOpen(batch, now)).as("a running batch keeps its pending rows").isZero();
        batches.cancel(batch, owner.id(), "member");

        assertThat(rows.cancelOpen(batch, now)).isEqualTo(3);
        assertThat(rows.cancelOpen(batch, now)).isZero();
        assertThat(rowStatuses(batch)).containsOnly(BatchRunRow.CANCELLED);
    }

    @Test
    void rowCountsForAPageOfBatchesIsOneStatement() {
        UserView owner = newUser("counts");
        UUID first = seedBatch(owner, "running", 1);
        UUID second = seedBatch(owner, "running", 1);
        UUID third = seedBatch(owner, "running", 1);
        seedRow(first, 0, BatchRunRow.PENDING, null, null);
        seedRow(first, 1, BatchRunRow.STARTING, null, Instant.now());
        seedRow(first, 2, BatchRunRow.RUNNING, null, null);
        seedRow(second, 0, BatchRunRow.COMPLETED, null, null);
        seedRow(second, 1, BatchRunRow.FAILED, null, null);
        seedRow(second, 2, BatchRunRow.CANCELLED, null, null);
        seedRow(third, 0, BatchRunRow.COMPLETED, null, null);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<BatchRowCounts> counts = rows.countsFor(List.of(first, second, third));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(counts).containsExactlyInAnyOrder(
                new BatchRowCounts(first, 2, 1, 0, 0, 0),
                new BatchRowCounts(second, 0, 0, 1, 1, 1),
                new BatchRowCounts(third, 0, 0, 1, 0, 0));
    }

    @Test
    void secondRetryOfOneParentViolatesTheUniqueIndex() {
        UserView owner = newUser("retry-unique");
        UUID parent = seedBatch(owner, "completed_with_errors", 1);
        UUID workflow = workflowOf(owner);

        seedBatch(owner, workflow, "running", 1, parent);

        assertThatThrownBy(() -> seedBatch(owner, workflow, "running", 1, parent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBatchPageIsNewestFirstFilteredByStatusAndOwnOnly() {
        UserView owner = newUser("page");
        UserView other = newUser("page-other");
        UUID workflow = workflowOf(owner);
        UUID oldest = seedBatch(owner, workflow, "completed", 1, null);
        UUID middle = seedBatch(owner, workflow, "running", 1, null);
        UUID newest = seedBatch(owner, workflow, "paused", 1, null);
        jdbc.update("UPDATE batch_run SET created_at = now() - interval '3 minutes' WHERE id = ?", oldest);
        jdbc.update("UPDATE batch_run SET created_at = now() - interval '2 minutes' WHERE id = ?", middle);
        jdbc.update("UPDATE batch_run SET created_at = now() - interval '1 minute' WHERE id = ?", newest);
        seedBatch(other, "running", 1);

        Page<BatchRunRepository.BatchWithWorkflowName> all = batches.findPageByOwner(owner.id(), null, PageRequest.of(0, 10));
        Page<BatchRunRepository.BatchWithWorkflowName> paused = batches.findPageByOwner(owner.id(), "paused", PageRequest.of(0, 10));

        assertThat(all.getContent()).extracting(row -> row.batch().getId()).containsExactly(newest, middle, oldest);
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent()).allSatisfy(row -> assertThat(row.workflowName()).isNotBlank());
        assertThat(paused.getContent()).extracting(row -> row.batch().getId()).containsExactly(newest);
        assertThat(batches.countUnfinishedByOwner(owner.id())).isEqualTo(2);
        assertThat(batches.findByIdAndOwnerId(middle, other.id())).isEmpty();
        assertThat(batches.findByIdAndOwnerId(middle, owner.id())).isPresent();
    }

    @Test
    void openBatchesComeLeastRecentlyDispatchedFirstAndFinishedOnesNever() {
        UserView owner = newUser("open");
        UUID never = seedBatch(owner, "running", 1);
        UUID early = seedBatch(owner, "paused", 1);
        UUID late = seedBatch(owner, "running", 1);
        UUID done = seedBatch(owner, "completed", 1);
        jdbc.update("UPDATE batch_run SET finished_at = now() WHERE id = ?", done);
        jdbc.update("UPDATE batch_run SET dispatched_at = now() - interval '1 hour' WHERE id = ?", early);
        jdbc.update("UPDATE batch_run SET dispatched_at = now() WHERE id IN (?, ?)", late, done);

        List<UUID> open = batches.findOpenForDispatch(PageRequest.of(0, 10_000));

        assertThat(open).contains(never, early, late).doesNotContain(done);
        assertThat(open.indexOf(never)).isLessThan(open.indexOf(early));
        assertThat(open.indexOf(early)).isLessThan(open.indexOf(late));
    }

    @Test
    void failedRowWhoseRunWasReopenedAndCompletedIsResynced() {
        UserView owner = newUser("resync");
        UUID workflow = workflowOf(owner);
        UUID batch = seedBatch(owner, workflow, "completed_with_errors", 5);
        UUID reopenedAndCompleted = seedRun(workflow, owner.id(), "completed", Instant.now());
        UUID reopenedAndLive = seedRun(workflow, owner.id(), "running", null);
        UUID stillError = seedRun(workflow, owner.id(), "error", Instant.now());
        seedRow(batch, 0, BatchRunRow.FAILED, reopenedAndCompleted, null);
        seedRow(batch, 1, BatchRunRow.FAILED, reopenedAndLive, null);
        seedRow(batch, 2, BatchRunRow.FAILED, stillError, null);
        seedRow(batch, 3, BatchRunRow.FAILED, null, null);
        jdbc.update("UPDATE batch_run_row SET error_code = 'runError', error_message = 'boom' WHERE batch_id = ?", batch);

        assertThat(rows.resyncFailedFromRuns(batch)).isEqualTo(1);

        assertThat(rowStatuses(batch)).containsExactly("completed", "failed", "failed", "failed");
        BatchRunRow resynced = rows.findByIdInOrderByRowIndex(rowIds(batch)).get(0);
        assertThat(resynced.getErrorCode()).isNull();
        assertThat(rows.findFailedOrdered(batch)).hasSize(3);
    }

    @Test
    void rowsPageByMemberFacingStatusAndAbandonedClaimsAreFoundByAge() {
        UserView owner = newUser("page-rows");
        UUID batch = seedBatch(owner, "running", 5);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        seedRow(batch, 0, BatchRunRow.PENDING, null, null);
        seedRow(batch, 1, BatchRunRow.STARTING, null, now.minus(LEASE.multipliedBy(3)));
        seedRow(batch, 2, BatchRunRow.STARTING, null, now);
        seedRow(batch, 3, BatchRunRow.RUNNING, null, null);

        assertThat(rows.findPage(batch, null, PageRequest.of(0, 10)).getContent())
                .extracting(BatchRunRow::getRowIndex).containsExactly(0, 1, 2, 3);
        assertThat(rows.findPage(batch, "pending", PageRequest.of(0, 10)).getContent())
                .extracting(BatchRunRow::getRowIndex).as("a starting row is pending to the member")
                .containsExactly(0, 1, 2);
        assertThat(rows.findPage(batch, "running", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(rows.findAbandonedStarting(batch, now.minus(LEASE.multipliedBy(2))))
                .extracting(BatchRunRow::getRowIndex).containsExactly(1);
        assertThat(rows.countLive(batch)).isEqualTo(3);
    }

    @Test
    void stopRequestedIsRecordedOnceUnderTheLease() {
        UserView owner = newUser("stop");
        UUID workflow = workflowOf(owner);
        UUID batch = seedBatch(owner, workflow, "cancelled", 2);
        UUID run = seedRun(workflow, owner.id(), "running", null);
        UUID row = seedRow(batch, 0, BatchRunRow.RUNNING, run, null);
        UUID token = leased(batch);

        assertThat(rows.findLiveNotStopRequested(batch)).extracting(BatchRunRow::getId).containsExactly(row);
        assertThat(rows.markStopRequested(row, UUID.randomUUID())).isZero();
        assertThat(rows.markStopRequested(row, token)).isEqualTo(1);
        assertThat(rows.markStopRequested(row, token)).isZero();
        assertThat(rows.findLiveNotStopRequested(batch)).isEmpty();
    }

    private UUID workflowOf(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
    }

    private UUID seedBatch(UserView owner, String status, int concurrency) {
        return seedBatch(owner, workflowOf(owner), status, concurrency);
    }

    private UUID seedBatch(UserView owner, UUID workflow, String status, int concurrency) {
        return seedBatch(owner, workflow, status, concurrency, null);
    }

    private UUID seedBatch(UserView owner, UUID workflow, String status, int concurrency, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO batch_run (id, owner_id, workflow_id, parent_batch_id, concurrency, acknowledge_cost, "
                        + "status, total_rows, created_at) VALUES (?, ?, ?, ?, ?, false, ?, 0, now())",
                id, owner.id(), workflow, parent, concurrency, status);
        return id;
    }

    /** A batch whose every row is final, so it may finish. */
    private UUID settledBatch(UserView owner, String status) {
        UUID batch = seedBatch(owner, status, 1);
        seedRow(batch, 0, BatchRunRow.COMPLETED, null, null);
        seedRow(batch, 1, BatchRunRow.FAILED, null, null);
        return batch;
    }

    private void seedRows(UUID batch, int count) {
        for (int i = 0; i < count; i++) {
            seedRow(batch, i, BatchRunRow.PENDING, null, null);
        }
    }

    private UUID seedRow(UUID batch, int index, String status, UUID runId, Instant claimedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO batch_run_row (id, batch_id, row_index, input, status, run_id, claimed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, batch, index, "row-" + index, status, runId, claimedAt == null ? null : Timestamp.from(claimedAt));
        return id;
    }

    private UUID seedRun(UUID workflow, UUID ownerId, String status, Instant finishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, 'seed', now() - interval '1 minute', ?)",
                id, workflow, ownerId, status, finishedAt == null ? null : Timestamp.from(finishedAt));
        return id;
    }

    /** Takes the batch's lease with a fresh token and returns it. */
    private UUID leased(UUID batch) {
        UUID token = UUID.randomUUID();
        Instant now = Instant.now();
        assertThat(batches.takeLease(batch, token, now, now.plus(LEASE))).isEqualTo(1);
        return token;
    }

    /** A cluster cap that leaves exactly {@code room} claims beyond the live rows already in the database. */
    private int clusterCap(int room) {
        Long live = jdbc.queryForObject(
                "SELECT count(*) FROM batch_run_row WHERE status IN ('starting', 'running')", Long.class);
        return (int) (live + room);
    }

    private boolean claimLockHeldByAnotherSession() {
        Long holders = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted "
                + "AND pid <> pg_backend_pid() AND objid = ((hashtext('workflow.batch.claim')::bigint "
                + "& 4294967295)::oid)", Long.class);
        return holders != null && holders > 0;
    }

    private boolean awaitClaimLockHeldByAnotherSession() {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (claimLockHeldByAnotherSession()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the claim lock", e);
            }
        }
        return false;
    }

    private UUID leaseToken(UUID batch) {
        return jdbc.queryForObject("SELECT lease_token FROM batch_run WHERE id = ?", UUID.class, batch);
    }

    private String batchStatus(UUID batch) {
        return jdbc.queryForObject("SELECT status FROM batch_run WHERE id = ?", String.class, batch);
    }

    private String pauseReason(UUID batch) {
        return jdbc.queryForObject("SELECT pause_reason FROM batch_run WHERE id = ?", String.class, batch);
    }

    private Timestamp finishedAt(UUID batch) {
        return jdbc.queryForObject("SELECT finished_at FROM batch_run WHERE id = ?", Timestamp.class, batch);
    }

    private List<String> rowStatuses(UUID batch) {
        return jdbc.queryForList("SELECT status FROM batch_run_row WHERE batch_id = ? ORDER BY row_index",
                String.class, batch);
    }

    private List<UUID> rowIds(UUID batch) {
        return jdbc.queryForList("SELECT id FROM batch_run_row WHERE batch_id = ? ORDER BY row_index",
                UUID.class, batch);
    }

    private List<Integer> rowIndexes(List<UUID> claimedIds) {
        return rows.findByIdInOrderByRowIndex(claimedIds).stream().map(BatchRunRow::getRowIndex).toList();
    }
}
