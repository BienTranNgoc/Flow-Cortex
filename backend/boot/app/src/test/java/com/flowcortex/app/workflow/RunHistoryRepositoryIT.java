package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStepAudioPeaks;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunHistoryRow;
import com.flowcortex.workflow.infrastructure.RunStepAudioPeaksRepository;
import com.flowcortex.workflow.infrastructure.RunUsageRow;
import com.flowcortex.workflow.infrastructure.WorkflowChoice;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The run-history reads and writes of {@link WorkflowRunRepository} and the stored waveforms,
 * against the real schema. Every test owns a fresh user, so owner-scoped reads see only its rows.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RunHistoryRepositoryIT extends AbstractWorkflowModuleIT {

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private RunStepAudioPeaksRepository peaks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void softDeleteRemovesOnlyAFinishedRunOfTheOwner() {
        UserView owner = newUser("soft-delete");
        UserView other = newUser("soft-delete-other");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        UUID completed = seedRun(workflow, owner.id(), "completed", now.minusSeconds(60), now.minusSeconds(30));
        UUID error = seedRun(workflow, owner.id(), "error", now.minusSeconds(60), now.minusSeconds(30));
        UUID cancelled = seedRun(workflow, owner.id(), "cancelled", now.minusSeconds(60), now.minusSeconds(30));
        UUID running = seedRun(workflow, owner.id(), "running", now.minusSeconds(60), null);
        UUID waiting = seedRun(workflow, owner.id(), "waiting_approval", now.minusSeconds(60), null);
        UUID cancelling = seedRun(workflow, owner.id(), "cancelling", now.minusSeconds(60), null);

        assertThat(softDelete(completed, owner.id(), now)).isEqualTo(1);
        assertThat(softDelete(error, owner.id(), now)).isEqualTo(1);
        assertThat(softDelete(cancelled, owner.id(), now)).isEqualTo(1);
        assertThat(softDelete(running, owner.id(), now)).isZero();
        assertThat(softDelete(waiting, owner.id(), now)).isZero();
        assertThat(softDelete(cancelling, owner.id(), now)).isZero();
        UUID othersCompleted = seedRun(workflow, owner.id(), "completed", now.minusSeconds(60), now.minusSeconds(30));
        assertThat(softDelete(othersCompleted, other.id(), now)).isZero();
        assertThat(softDelete(completed, owner.id(), now.plusSeconds(5))).isZero();
        assertThat(deletedAt(completed)).isEqualTo(Timestamp.from(now));
        assertThat(deletedAt(running)).isNull();
        assertThat(deletedAt(othersCompleted)).isNull();
    }

    @Test
    void findByIdAndOwnerIdNoLongerFindsADeletedRun() {
        UserView owner = newUser("find-deleted");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now();
        UUID kept = seedRun(workflow, owner.id(), "completed", now.minusSeconds(60), now.minusSeconds(30));
        UUID deleted = seedRun(workflow, owner.id(), "completed", now.minusSeconds(60), now.minusSeconds(30));
        softDelete(deleted, owner.id(), now);

        assertThat(runs.findByIdAndOwnerId(kept, owner.id())).isPresent();
        assertThat(runs.findByIdAndOwnerId(deleted, owner.id())).isEmpty();
        assertThat(runs.findById(deleted)).as("the engine still reads a deleted run by id").isPresent();
    }

    @Test
    void historyPageIsTheOwnersLiveRunsNewestFirstWithEveryFilter() {
        UserView owner = newUser("history");
        UserView other = newUser("history-other");
        WorkflowView dub = workflowOf(owner);
        WorkflowView clip = workflowOf(owner);
        Instant base = Instant.now().minus(Duration.ofDays(10));
        UUID oldest = seedRun(dub.id(), owner.id(), "completed", base, base.plusSeconds(5));
        UUID failed = seedRun(dub.id(), owner.id(), "error", base.plusSeconds(100), base.plusSeconds(105));
        UUID starred = seedRun(clip.id(), owner.id(), "completed", base.plusSeconds(200), base.plusSeconds(205));
        jdbc.update("UPDATE workflow_run SET favorite = true, title = 'Launch cut' WHERE id = ?", starred);
        UUID newest = seedRun(clip.id(), owner.id(), "running", base.plusSeconds(300), null);
        UUID removed = seedRun(clip.id(), owner.id(), "completed", base.plusSeconds(400), base.plusSeconds(405));
        softDelete(removed, owner.id(), base.plusSeconds(500));
        seedRun(dub.id(), other.id(), "completed", base.plusSeconds(250), base.plusSeconds(255));

        assertThat(ids(history(owner, null, null, null, null, false)))
                .containsExactly(newest, starred, failed, oldest);
        assertThat(ids(history(owner, RunStatus.ERROR, null, null, null, false))).containsExactly(failed);
        assertThat(ids(history(owner, null, dub.id(), null, null, false))).containsExactly(failed, oldest);
        assertThat(ids(history(owner, null, null, base.plusSeconds(100), base.plusSeconds(300), false)))
                .as("from is inclusive, to is exclusive").containsExactly(starred, failed);
        assertThat(ids(history(owner, null, null, null, null, true))).containsExactly(starred);
        assertThat(ids(history(owner, RunStatus.COMPLETED, clip.id(), base, base.plusSeconds(1000), true)))
                .containsExactly(starred);
        Page<RunHistoryRow> page = runs.findHistory(owner.id(), null, null, null, null, false, PageRequest.of(0, 2));
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(RunHistoryRow::workflowName)
                .containsExactly(clip.name(), clip.name());
        assertThat(page.getContent().get(1).run().getTitle()).isEqualTo("Launch cut");
        assertThat(page.getContent().get(1).run().isFavorite()).isTrue();
    }

    @Test
    void aHundredRowPageIsServedInThreeStatements() {
        UserView owner = newUser("statements");
        UUID workflow = workflowOf(owner).id();
        Instant base = Instant.now().minus(Duration.ofDays(2));
        for (int i = 0; i < 100; i++) {
            seedRun(workflow, owner.id(), "completed", base.plusSeconds(i), base.plusSeconds(i + 1));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Page<RunHistoryRow> page = runs.findHistory(owner.id(), null, null, null, null, false, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(100);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        assertThat(page.getContent()).allSatisfy(row -> assertThat(row.workflowName()).isNotNull());
        assertThat(statistics.getPrepareStatementCount()).as("reading the names issues no further statement")
                .isLessThanOrEqualTo(3);
    }

    @Test
    void workflowOptionsAreDistinctSortedAndKeepDeletedWorkflows() {
        UserView owner = newUser("options");
        UserView other = newUser("options-other");
        Instant now = Instant.now();
        UUID bravo = workflowNamed(owner, "Bravo " + UUID.randomUUID());
        UUID alpha = workflowNamed(owner, "Alpha " + UUID.randomUUID());
        UUID gone = workflowNamed(owner, "Charlie " + UUID.randomUUID());
        UUID unused = workflowNamed(owner, "Delta " + UUID.randomUUID());
        seedRun(bravo, owner.id(), "completed", now.minusSeconds(90), now.minusSeconds(80));
        seedRun(bravo, owner.id(), "error", now.minusSeconds(70), now.minusSeconds(60));
        seedRun(alpha, owner.id(), "completed", now.minusSeconds(50), now.minusSeconds(40));
        seedRun(gone, owner.id(), "completed", now.minusSeconds(30), now.minusSeconds(20));
        seedRun(unused, other.id(), "completed", now.minusSeconds(30), now.minusSeconds(20));
        jdbc.update("UPDATE workflow SET deleted_at = now() WHERE id = ?", gone);
        UUID onlyDeletedRuns = workflowNamed(owner, "Echo " + UUID.randomUUID());
        UUID deletedRun = seedRun(onlyDeletedRuns, owner.id(), "completed", now.minusSeconds(30), now.minusSeconds(20));
        softDelete(deletedRun, owner.id(), now);

        List<WorkflowChoice> options = runs.findHistoryWorkflows(owner.id(), PageRequest.of(0, 100));

        assertThat(options).extracting(WorkflowChoice::id).containsExactly(alpha, bravo, gone);
        assertThat(options).extracting(WorkflowChoice::name).isSorted();
    }

    @Test
    void trayBucketsSplitLiveAndRecentFinished() {
        UserView owner = newUser("tray");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now();
        UUID running = seedRun(workflow, owner.id(), "running", now.minus(Duration.ofHours(2)), null);
        UUID waiting = seedRun(workflow, owner.id(), "waiting_approval", now.minus(Duration.ofHours(1)), null);
        UUID completed = seedRun(workflow, owner.id(), "completed", now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1)));
        UUID longRun = seedRun(workflow, owner.id(), "error", now.minus(Duration.ofHours(30)),
                now.minus(Duration.ofMinutes(5)));
        seedRun(workflow, owner.id(), "cancelled", now.minus(Duration.ofHours(26)), now.minus(Duration.ofHours(25)));
        UUID deleted = seedRun(workflow, owner.id(), "completed", now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofMinutes(2)));
        softDelete(deleted, owner.id(), now);
        UUID capped = seedRun(workflow, owner.id(), "completed", now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofMinutes(1)));

        List<RunHistoryRow> active = runs.findTrayActive(owner.id(), PageRequest.of(0, 50));
        List<RunHistoryRow> recent = runs.findTrayRecent(owner.id(), now.minus(Duration.ofHours(24)),
                PageRequest.of(0, 15));
        List<RunHistoryRow> firstOne = runs.findTrayRecent(owner.id(), now.minus(Duration.ofHours(24)),
                PageRequest.of(0, 1));

        assertThat(ids(active)).containsExactly(waiting, running);
        assertThat(ids(recent)).as("finished within 24 h, most recently finished first")
                .containsExactly(capped, longRun, completed);
        assertThat(ids(firstOne)).containsExactly(capped);
    }

    @Test
    void usageRowsKeepDeletedRunsInsideTheWindow() {
        UserView owner = newUser("usage");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now();
        UUID inside = seedRun(workflow, owner.id(), "completed", now.minus(Duration.ofDays(1)),
                now.minus(Duration.ofDays(1)).plusSeconds(5));
        UUID deleted = seedRun(workflow, owner.id(), "error", now.minus(Duration.ofDays(2)),
                now.minus(Duration.ofDays(2)).plusSeconds(5));
        seedRun(workflow, owner.id(), "completed", now.minus(Duration.ofDays(40)),
                now.minus(Duration.ofDays(40)).plusSeconds(5));
        softDelete(deleted, owner.id(), now);

        List<RunUsageRow> rows = runs.findUsageRows(owner.id(), now.minus(Duration.ofDays(30)));

        assertThat(rows).extracting(RunUsageRow::runId).containsExactly(inside, deleted);
        assertThat(rows.get(1).status()).isEqualTo(RunStatus.ERROR);
        assertThat(rows.get(0).workflowId()).isEqualTo(workflow);
    }

    @Test
    void aRunSavedWithoutATraceIdGetsOne() {
        UserView owner = newUser("trace");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now();
        UUID bare = UUID.randomUUID();
        UUID fixed = UUID.randomUUID();
        runs.save(new WorkflowRun(bare, workflow, owner.id(), "input", now));
        runs.save(new WorkflowRun(fixed, workflow, owner.id(), "input", now)
                .withTraceId("4bf92f3577b34da6a3ce929d0e0e4736"));

        assertThat(traceId(bare)).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        assertThat(traceId(fixed)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void anEngineWriteDoesNotRevertATitleOrADelete() {
        UserView owner = newUser("dynamic-update");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now();
        UUID id = seedRun(workflow, owner.id(), "running", now.minusSeconds(60), null);
        transactions.executeWithoutResult(status -> {
            WorkflowRun loaded = runs.findById(id).orElseThrow();
            jdbc.update("UPDATE workflow_run SET title = 'Renamed', favorite = true WHERE id = ?", id);
            loaded.markWaitingApproval();
            runs.flush();
        });

        assertThat(jdbc.queryForMap("SELECT status, title, favorite FROM workflow_run WHERE id = ?", id))
                .containsEntry("status", "waiting_approval").containsEntry("title", "Renamed")
                .containsEntry("favorite", true);
    }

    @Test
    void peaksInsertIsFirstWriterWins() {
        UserView owner = newUser("peaks");
        UUID workflow = workflowOf(owner).id();
        Instant now = Instant.now();
        UUID run = seedRun(workflow, owner.id(), "completed", now.minusSeconds(60), now.minusSeconds(30));
        UUID step = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 1, 'tts', 'completed')",
                step, run);

        int first = peaks.insertIfAbsent(step, (short) 1, "speech.mp3", "[0.1,0.9]", now);
        int second = peaks.insertIfAbsent(step, (short) 1, "speech.mp3", "[0.5,0.5]", now.plusSeconds(1));
        int undecodable = peaks.insertIfAbsent(step, (short) 2, "speech.mp3", null, now);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(undecodable).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT peaks::text FROM run_step_audio_peaks WHERE run_step_id = ? "
                + "AND attempt = 1 AND output_name = 'speech.mp3'", String.class, step)).isEqualTo("[0.1, 0.9]");
        RunStepAudioPeaks stored = peaks.findById(new RunStepAudioPeaks.Key(step, (short) 1, "speech.mp3"))
                .orElseThrow();
        assertThat(stored.getPeaks()).contains("0.1").contains("0.9");
        assertThat(peaks.findById(new RunStepAudioPeaks.Key(step, (short) 2, "speech.mp3")).orElseThrow()
                .getPeaks()).as("a sound that cannot be decoded is recorded as null").isNull();
    }

    private WorkflowView workflowOf(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
    }

    private UUID workflowNamed(UserView owner, String name) {
        return workflowService.createWorkflow(owner.id(), name, false,
                List.of(new NewWorkflowStep("only", "echo", null))).id();
    }

    private List<RunHistoryRow> history(UserView owner, RunStatus status, UUID workflowId, Instant from, Instant to,
            boolean favoriteOnly) {
        return runs.findHistory(owner.id(), status, workflowId, from, to, favoriteOnly, PageRequest.of(0, 50))
                .getContent();
    }

    private static List<UUID> ids(List<RunHistoryRow> rows) {
        return rows.stream().map(row -> row.run().getId()).toList();
    }

    private int softDelete(UUID id, UUID ownerId, Instant now) {
        Integer deleted = transactions.execute(status -> {
            runs.lockById(id);
            return runs.softDelete(id, ownerId, now);
        });
        return deleted;
    }

    private Object deletedAt(UUID id) {
        return jdbc.queryForObject("SELECT deleted_at FROM workflow_run WHERE id = ?", Timestamp.class, id);
    }

    private String traceId(UUID id) {
        return jdbc.queryForObject("SELECT trace_id FROM workflow_run WHERE id = ?", String.class, id);
    }

    private UUID seedRun(UUID workflowId, UUID ownerId, String status, Instant startedAt, Instant finishedAt) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, 'seed', ?, ?)",
                runId, workflowId, ownerId, status, Timestamp.from(startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt));
        return runId;
    }
}
