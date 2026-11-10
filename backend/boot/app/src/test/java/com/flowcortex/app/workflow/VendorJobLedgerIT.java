package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.media.api.MediaVendorTaskReported;
import com.flowcortex.media.api.VendorTaskReport;
import com.flowcortex.media.api.provider.VendorLedger;
import com.flowcortex.workflow.application.vendor.VendorJobLedger;
import com.flowcortex.workflow.application.vendor.VendorTaskCleanupJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The vendor-job ledger against real Postgres: claim, record, consume, verdict and sweep, each on
 * committed seed rows (the ledger's write paths open their own transactions, so a rolled-back test
 * transaction would hide them). Every seeded owner is deleted after the test.
 */
@Testcontainers
@SpringBootTest
class VendorJobLedgerIT extends AbstractPostgresBootTest {

    private static final String KEY = "a".repeat(64);
    private static final String FINGERPRINT = "f".repeat(64);
    private static final String CAPABILITY = "generate_video";

    @Autowired
    private VendorJobLedger ledger;

    @Autowired
    private VendorTaskCleanupJob cleanup;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher events;

    private final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID owner : owners) {
            jdbc.update("DELETE FROM vendor_task WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM run_step WHERE run_id IN (SELECT id FROM workflow_run WHERE owner_id = ?)",
                    owner);
            jdbc.update("DELETE FROM workflow_run WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM workflow WHERE owner_id = ?", owner);
        }
    }

    @Test
    void aNewRunPicksUpARowWhoseHoldingStepIsNoLongerRunning() {
        Run first = run();
        Run second = run(first.owner);
        UUID row = row(first.owner, KEY, first.step("error"), null, "running", 0);

        List<VendorLedger.Candidate> candidates = claim(second, second.step("running"));

        assertThat(candidates).extracting(VendorLedger.Candidate::vendorTaskId).containsExactly(row);
        assertThat(heldBy(row)).isEqualTo(second.lastStep);
    }

    @Test
    void aRowHeldByAStillRunningStepIsNotACandidate() {
        Run first = run();
        Run second = run(first.owner);
        row(first.owner, KEY, first.step("running"), null, "running", 0);

        assertThat(claim(second, second.step("running"))).isEmpty();
    }

    @Test
    void anotherOwnerNeverSeesTheRow() {
        Run mine = run();
        Run theirs = run();
        row(mine.owner, KEY, null, null, "running", 0);

        assertThat(claim(theirs, theirs.step("running"))).isEmpty();
    }

    @Test
    void moreThanFiveMatchesYieldTheOldestFiveAndTheHolderIsUpdated() {
        Run run = run();
        List<UUID> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(row(run.owner, KEY, null, null, "running", 7 - i));
        }

        List<VendorLedger.Candidate> candidates = claim(run, run.step("running"));

        assertThat(candidates).extracting(VendorLedger.Candidate::vendorTaskId).containsExactlyElementsOf(rows.subList(0, 5));
        assertThat(heldBy(rows.get(0))).isEqualTo(run.lastStep);
        assertThat(heldBy(rows.get(6))).isNull();
    }

    @Test
    void theClaimingStepsOwnRowIsFirstEvenPastTheCap() {
        Run run = run();
        UUID step = run.step("running");
        List<UUID> unheld = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            unheld.add(row(run.owner, KEY, null, null, "running", 20 - i));
        }
        UUID own = row(run.owner, KEY, null, step, "running", 1);

        List<UUID> ids = claim(run, step).stream().map(VendorLedger.Candidate::vendorTaskId).toList();

        assertThat(ids).hasSize(5);
        assertThat(ids.getFirst()).isEqualTo(own);
        assertThat(ids.subList(1, 5)).containsExactlyElementsOf(unheld.subList(0, 4));
    }

    @Test
    void aConsumedOrEndedRowIsNeverACandidate() {
        Run run = run();
        UUID consumed = row(run.owner, KEY, null, null, "succeeded", 5);
        jdbc.update("UPDATE vendor_task SET consumed_at = now(), consumed_by_step_id = ? WHERE id = ?",
                run.step("completed"), consumed);
        row(run.owner, KEY, null, null, "gone", 4);
        row(run.owner, KEY, null, null, "failed", 3);

        assertThat(claim(run, run.step("running"))).isEmpty();
    }

    @Test
    void theRunRowLockedByTheDispatchingTransactionDoesNotHideCandidates() {
        Run run = run();
        UUID step = run.step("running");
        UUID row = row(run.owner, KEY, null, null, "running", 0);

        List<VendorLedger.Candidate> candidates = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList("SELECT id FROM workflow_run WHERE id = ? FOR UPDATE", run.id);
            return ledger.claimCandidates(run.id, step, CAPABILITY, KEY);
        });

        assertThat(candidates).extracting(VendorLedger.Candidate::vendorTaskId).containsExactly(row);
    }

    @Test
    void aClaimNotYetCommittedHidesTheRowFromAConcurrentClaim() throws Exception {
        Run run = run();
        Run other = run(run.owner);
        UUID firstStep = run.step("running");
        UUID secondStep = other.step("running");
        UUID row = row(run.owner, KEY, null, null, "running", 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier bothClaimed = new CyclicBarrier(2);
            Future<List<VendorLedger.Candidate>> first = pool.submit(holdingClaim(run, firstStep, bothClaimed));
            Future<List<VendorLedger.Candidate>> second = pool.submit(holdingClaim(other, secondStep, bothClaimed));

            List<VendorLedger.Candidate> a = first.get(30, TimeUnit.SECONDS);
            List<VendorLedger.Candidate> b = second.get(30, TimeUnit.SECONDS);

            assertThat(a.size() + b.size()).isEqualTo(1);
            assertThat(heldBy(row)).isIn(firstStep, secondStep);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aClaimOnAnUncommittedClaimOfTheSameOwnerAndKeyGetsNothing() {
        Run run = run();
        Run other = run(run.owner);
        UUID firstStep = run.step("running");
        UUID secondStep = other.step("running");
        row(run.owner, KEY, null, null, "running", 0);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<VendorLedger.Candidate> second = tx.execute(outer -> {
            assertThat(ledger.claimCandidates(run.id, firstStep, CAPABILITY, KEY)).hasSize(1);
            // A separate connection stands for the concurrent dispatch.
            return CompletableFutureHelper.inNewThread(() -> tx.execute(inner ->
                    ledger.claimCandidates(other.id, secondStep, CAPABILITY, KEY)));
        });

        assertThat(second).isEmpty();
    }

    @Test
    void aSqlErrorInsideTheClaimRollsBackToTheSavepointAndTheOuterTransactionStillCommits() {
        Run run = run();
        UUID step = run.step("running");
        row(run.owner, KEY, null, null, "running", 0);
        UUID marker = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE vendor_task RENAME TO vendor_task_renamed");
            List<VendorLedger.Candidate> candidates = ledger.claimCandidates(run.id, step, CAPABILITY, KEY);
            jdbc.execute("ALTER TABLE vendor_task_renamed RENAME TO vendor_task");
            assertThat(candidates).isEmpty();
            jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                    + " VALUES (?, ?, 'after claim error', false, now(), now())", marker, run.owner);
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow WHERE id = ?", Integer.class, marker))
                .isEqualTo(1);
    }

    @Test
    void aRecordIsStoredOnceEvenWhenDeliveredTwiceAndAfterTheStepClosed() {
        Run run = run();
        UUID step = run.step("error");
        VendorTaskReport report = newSubmit("cgt-1", FINGERPRINT);

        ledger.record(run.id, step, report);
        ledger.record(run.id, step, report);

        assertThat(count(run.owner, "cgt-1")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, requested_by_step_id, consumed_at FROM vendor_task"
                + " WHERE owner_id = ?", run.owner))
                .containsEntry("status", "running").containsEntry("requested_by_step_id", step)
                .containsEntry("consumed_at", null);
    }

    @Test
    void aStepThatSubmitsAgainRetiresItsEarlierRowWithTheSameFingerprint() {
        Run run = run();
        UUID step = run.step("running");

        ledger.record(run.id, step, newSubmit("cgt-1", FINGERPRINT));
        ledger.record(run.id, step, newSubmit("cgt-2", FINGERPRINT));

        assertThat(statusOf("cgt-1")).isEqualTo("gone");
        assertThat(statusOf("cgt-2")).isEqualTo("running");
    }

    @Test
    void aCompletedResultConsumesTheRowAndNoLaterDispatchCarriesIt() {
        Run run = run();
        UUID submitter = run.step("error");
        ledger.record(run.id, submitter, newSubmit("cgt-1", FINGERPRINT));
        Run second = run(run.owner);
        UUID consumer = second.step("running");

        ledger.consume(second.id, consumer, CAPABILITY, resultData("cgt-1", KEY, FINGERPRINT));

        assertThat(jdbc.queryForMap("SELECT status, consumed_by_step_id FROM vendor_task WHERE owner_id = ?",
                run.owner)).containsEntry("status", "succeeded").containsEntry("consumed_by_step_id", consumer);
        assertThat(claim(second, second.step("running"))).isEmpty();
    }

    @Test
    void aResultThatOvertakesItsRecordLeavesOneConsumedRow() {
        Run run = run();
        UUID step = run.step("running");

        ledger.consume(run.id, step, CAPABILITY, resultData("cgt-9", KEY, FINGERPRINT));
        ledger.record(run.id, step, newSubmit("cgt-9", FINGERPRINT));

        assertThat(count(run.owner, "cgt-9")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM vendor_task WHERE owner_id = ?",
                Boolean.class, run.owner)).isTrue();
    }

    @Test
    void aVerdictOnAConsumedRowLeavesItConsumed() {
        Run run = run();
        UUID step = run.step("running");
        ledger.consume(run.id, step, CAPABILITY, resultData("cgt-1", KEY, FINGERPRINT));
        UUID row = jdbc.queryForObject("SELECT id FROM vendor_task WHERE owner_id = ?", UUID.class, run.owner);

        ledger.mark(run.id, step, row, VendorTaskReport.Status.GONE);

        assertThat(statusOf("cgt-1")).isEqualTo("succeeded");
    }

    @Test
    void aVerdictFromAnotherOwnersRunChangesNothing() {
        Run mine = run();
        Run theirs = run();
        UUID row = row(mine.owner, KEY, null, null, "running", 0);

        ledger.mark(theirs.id, theirs.step("running"), row, VendorTaskReport.Status.GONE);

        assertThat(jdbc.queryForObject("SELECT status FROM vendor_task WHERE id = ?", String.class, row))
                .isEqualTo("running");
    }

    @Test
    void aVerdictByVendorJobIdMarksTheRowOfTheSameOwnerOnly() {
        Run mine = run();
        Run theirs = run();
        UUID step = mine.step("running");
        ledger.record(mine.id, step, newSubmit("cgt-8", FINGERPRINT));

        ledger.markByJob(theirs.id, theirs.step("running"), "seedance", "cgt-8", VendorTaskReport.Status.FAILED);
        assertThat(statusOf("cgt-8")).isEqualTo("running");

        ledger.markByJob(mine.id, step, "seedance", "cgt-8", VendorTaskReport.Status.FAILED);
        assertThat(statusOf("cgt-8")).isEqualTo("failed");
    }

    @Test
    void aVerdictByVendorJobIdNeverReopensAConsumedRow() {
        Run run = run();
        UUID step = run.step("running");
        ledger.consume(run.id, step, CAPABILITY, resultData("cgt-9", KEY, FINGERPRINT));

        ledger.markByJob(run.id, step, "seedance", "cgt-9", VendorTaskReport.Status.GONE);

        assertThat(statusOf("cgt-9")).isEqualTo("succeeded");
    }

    @Test
    void aPublishedVendorTaskReportReachesTheLedger() {
        Run run = run();
        UUID step = run.step("running");

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                events.publishEvent(new MediaVendorTaskReported(newSubmit("cgt-7", FINGERPRINT), run.id, step)));

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count(run.owner, "cgt-7")).isEqualTo(1));
    }

    @Test
    void theSweepDeletesAnEightDayRowAndKeepsASixDayRowEvenWhenRunTwice() throws Exception {
        Run run = run();
        UUID old = row(run.owner, KEY, null, null, "running", 8 * 24 * 60);
        UUID recent = row(run.owner, KEY, null, null, "running", 6 * 24 * 60);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(cleanup::sweep);
            Future<?> b = pool.submit(cleanup::sweep);
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForList("SELECT id FROM vendor_task WHERE owner_id = ?", UUID.class, run.owner))
                .containsExactly(recent).doesNotContain(old);
    }

    private Callable<List<VendorLedger.Candidate>> holdingClaim(Run run, UUID step, CyclicBarrier barrier) {
        return () -> new TransactionTemplate(transactionManager).execute(status -> {
            List<VendorLedger.Candidate> claimed = ledger.claimCandidates(run.id, step, CAPABILITY, KEY);
            try {
                barrier.await(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return claimed;
        });
    }

    private List<VendorLedger.Candidate> claim(Run run, UUID step) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> ledger.claimCandidates(run.id, step, CAPABILITY, KEY));
    }

    private static VendorTaskReport newSubmit(String jobId, String fingerprint) {
        return new VendorTaskReport(null, "seedance", "seedance-1", fingerprint, KEY, jobId, Map.of(),
                VendorTaskReport.Status.RUNNING, Instant.now());
    }

    private static Map<String, Object> resultData(String jobId, String requestKey, String fingerprint) {
        return Map.of("provider", "seedance", "model", "seedance-1", "providerTaskId", jobId,
                "requestKey", requestKey, "fingerprint", fingerprint);
    }

    private UUID row(UUID owner, String key, UUID requestedBy, UUID heldBy, String status, int minutesOld) {
        UUID id = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.now().minus(Duration.ofMinutes(minutesOld)));
        jdbc.update("INSERT INTO vendor_task (id, owner_id, capability, request_key, fingerprint, vendor, model,"
                + " vendor_job_id, status, vendor_created_at, requested_by_step_id, held_by_step_id, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, 'seedance', 'seedance-1', ?, ?, ?, ?, ?, ?, ?)",
                id, owner, CAPABILITY, key, FINGERPRINT, "job-" + id, status, created, requestedBy, heldBy, created,
                created);
        return id;
    }

    private UUID heldBy(UUID row) {
        return jdbc.queryForObject("SELECT held_by_step_id FROM vendor_task WHERE id = ?", UUID.class, row);
    }

    private String statusOf(String jobId) {
        return jdbc.queryForObject("SELECT status FROM vendor_task WHERE vendor_job_id = ?", String.class, jobId);
    }

    private int count(UUID owner, String jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM vendor_task WHERE owner_id = ? AND vendor_job_id = ?",
                Integer.class, owner, jobId);
    }

    private Run run() {
        return run(UUID.randomUUID());
    }

    private Run run(UUID owner) {
        if (!owners.contains(owner)) {
            owners.add(owner);
        }
        UUID workflow = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                + " VALUES (?, ?, 'ledger', false, now(), now())", workflow, owner);
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)"
                + " VALUES (?, ?, ?, 'running', now())", run, workflow, owner);
        return new Run(run, owner);
    }

    /** A run row of a seeded owner; each {@link #step} adds one step in the given status. */
    private final class Run {
        private final UUID id;
        private final UUID owner;
        private int order;
        private UUID lastStep;

        private Run(UUID id, UUID owner) {
            this.id = id;
            this.owner = owner;
        }

        UUID step(String status) {
            lastStep = UUID.randomUUID();
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, ?, ?, ?)",
                    lastStep, id, ++order, CAPABILITY, status);
            return lastStep;
        }
    }

    /** Runs a task on another thread, so it gets its own database connection and transaction. */
    private static final class CompletableFutureHelper {

        static <T> T inNewThread(Callable<T> task) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                return pool.submit(task).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
