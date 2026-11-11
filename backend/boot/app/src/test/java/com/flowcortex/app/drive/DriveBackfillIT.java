package com.flowcortex.app.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.drive.backfill.DriveBackfill;
import com.flowcortex.drive.memory.MemoryPopulation;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The backfill end to end: runs are completed (the live listener populates them), their Memory rows are removed to
 * stand for runs that finished before Drive existed, and {@link DriveBackfill#run} is called directly so the JVM is
 * never asked to exit.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class DriveBackfillIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DriveBackfill backfill;

    @Autowired
    MemoryPopulation population;

    record MigrationView(Instant migratedAt, Counts counts) {
        record Counts(int runs, int results) {
        }
    }

    record ProblemView(int status, String detail) {
    }

    @Test
    void dryRunWritesNothingAndCountsWhatApplyWouldWrite(CapturedOutput output) {
        UserView owner = newUser("backfill-dry");
        UUID space = awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 3);

        int code = backfill.run("dry-run", false);

        assertThat(code).isZero();
        assertThat(entryCount(runs)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_backfill WHERE space_id = ?", Long.class, space))
                .isZero();
        assertThat(output.getOut()).contains("dry-run would write ownerId=" + owner.id() + " spaceId=" + space
                + " runs=3 results=3");
    }

    @Test
    void applyTwiceWritesNoNewEntryKeepsFlagsUpdatedAtAndTheFirstMigratedAt() {
        UserView owner = newUser("backfill-twice");
        UUID space = awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 2);

        assertThat(backfill.run("apply", false)).isZero();

        assertThat(entryCount(runs)).isEqualTo(2);
        UUID pinned = jdbc.queryForObject("SELECT id FROM memory_result WHERE run_id = ?", UUID.class, runs.get(0));
        jdbc.update("UPDATE memory_result SET pinned = true WHERE id = ?", pinned);
        Timestamp migratedAt = migratedAt(space);
        List<Timestamp> updatedAt = jdbc.queryForList(
                "SELECT updated_at FROM memory_result WHERE run_id IN (?, ?) ORDER BY id", Timestamp.class,
                runs.get(0), runs.get(1));
        Map<String, Object> firstCounts = jdbc.queryForMap("SELECT counts FROM drive_backfill WHERE space_id = ?",
                space);

        assertThat(backfill.run("apply", false)).isZero();

        assertThat(entryCount(runs)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT pinned FROM memory_result WHERE id = ?", Boolean.class, pinned))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT updated_at FROM memory_result WHERE run_id IN (?, ?) ORDER BY id",
                Timestamp.class, runs.get(0), runs.get(1))).isEqualTo(updatedAt);
        assertThat(migratedAt(space)).isEqualTo(migratedAt);
        assertThat(jdbc.queryForMap("SELECT counts FROM drive_backfill WHERE space_id = ?", space))
                .isEqualTo(firstCounts);
        assertThat(counts(space)).containsExactly(2, 2);
    }

    @Test
    void aMemberPopulatedLiveOnlyHasNoMigrationRecordAfterApply() {
        UserView owner = newUser("backfill-live");
        UUID space = awaitSpace(owner.id());
        UUID run = completedEchoRun(owner, "live only");
        awaitEntries(run, 1);

        assertThat(backfill.run("apply", false)).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_backfill WHERE space_id = ?", Long.class, space))
                .isZero();
        ResponseEntity<MigrationView> read = get("/api/v1/me/drive-migration", loginAs(owner), MigrationView.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().migratedAt()).isNull();
        assertThat(read.getBody().counts()).isNull();
    }

    @Test
    void countsAccumulateOnlyInsertedRunsAndEntries() {
        UserView owner = newUser("backfill-counts");
        UUID space = awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 2);
        restoreEntry(runs.get(1));

        assertThat(backfill.run("apply", false)).isZero();
        assertThat(counts(space)).containsExactly(1, 1);
        Timestamp first = migratedAt(space);

        jdbc.update("DELETE FROM memory_result WHERE run_id = ?", runs.get(1));
        assertThat(backfill.run("apply", false)).isZero();

        assertThat(counts(space)).containsExactly(2, 2);
        assertThat(migratedAt(space)).isEqualTo(first);
    }

    @Test
    void anUnreadableOutputsRowIsReportedSkippedAndExits1(CapturedOutput output) {
        UserView owner = newUser("backfill-bad");
        awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 3);
        UUID broken = runs.get(1);
        jdbc.update("UPDATE run_step SET outputs = '\"not a list\"'::jsonb WHERE run_id = ?", broken);
        try {
            int code = backfill.run("apply", false);

            assertThat(code).isEqualTo(1);
            assertThat(entryCount(List.of(runs.get(0), runs.get(2)))).isEqualTo(2);
            assertThat(entryCount(List.of(broken))).isZero();
            assertThat(output.getOut()).contains("skipped run runId=" + broken + " error=IllegalStateException");
        } finally {
            jdbc.update("UPDATE run_step SET outputs = NULL WHERE run_id = ?", broken);
        }
    }

    @Test
    void reconcileReportsAMissingEntryWithExit3AndSavesOnlyWithSave(CapturedOutput output) {
        UserView owner = newUser("backfill-recon");
        UUID space = awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 2);
        restoreEntry(runs.get(0));

        assertThat(backfill.run("reconcile", false)).isEqualTo(3);

        assertThat(output.getOut()).contains("reconcile mismatch ownerId=" + owner.id() + " spaceId=" + space
                + " projected=2 stored=1 missing=1 extra=0 runIds=[" + runs.get(1) + "]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_backfill WHERE space_id = ?", Long.class, space))
                .isZero();

        assertThat(backfill.run("reconcile", true)).isEqualTo(3);

        Map<String, Object> saved = jdbc.queryForMap("SELECT reconciled_at, ok, migrated_at FROM drive_backfill "
                + "WHERE space_id = ?", space);
        assertThat(saved.get("reconciled_at")).isNotNull();
        assertThat(saved).containsEntry("ok", false).containsEntry("migrated_at", null);
        assertThat(entryCount(runs)).isEqualTo(1);
    }

    @Test
    void aRunWhosePublicationWasExhaustedIsReportedThenBroughtInByApply(CapturedOutput output) {
        UserView owner = newUser("backfill-exhausted");
        UUID space = awaitSpace(owner.id());
        UUID run = completedEchoRun(owner, "publication lost");
        awaitEntries(run, 1);
        jdbc.update("DELETE FROM memory_result WHERE run_id = ?", run);

        assertThat(backfill.run("reconcile", false)).isEqualTo(3);
        assertThat(output.getOut()).contains("spaceId=" + space).contains("runIds=[" + run + "]");

        assertThat(backfill.run("apply", false)).isZero();
        assertThat(backfill.run("reconcile", true)).isZero();

        assertThat(entryCount(List.of(run))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ok FROM drive_backfill WHERE space_id = ?", Boolean.class, space))
                .isTrue();
    }

    @Test
    void anUnknownModeIs2AndWritesNothing() {
        UserView owner = newUser("backfill-mode");
        awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 1);
        long backfills = jdbc.queryForObject("SELECT count(*) FROM drive_backfill", Long.class);

        assertThat(backfill.run("migrate", true)).isEqualTo(2);

        assertThat(entryCount(runs)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_backfill", Long.class)).isEqualTo(backfills);
    }

    @Test
    void meDriveMigrationReturnsTheRecordOrNulls() {
        UserView migrated = newUser("backfill-me");
        UserView untouched = newUser("backfill-me-none");
        awaitSpace(migrated.id());
        awaitSpace(untouched.id());
        earlierRuns(migrated, 2);
        assertThat(backfill.run("apply", false)).isZero();

        MigrationView mine = get("/api/v1/me/drive-migration", loginAs(migrated), MigrationView.class).getBody();
        MigrationView none = get("/api/v1/me/drive-migration", loginAs(untouched), MigrationView.class).getBody();

        assertThat(mine.migratedAt()).isNotNull();
        assertThat(mine.counts()).isEqualTo(new MigrationView.Counts(2, 2));
        assertThat(none.migratedAt()).isNull();
        assertThat(none.counts()).isNull();
        assertThat(get("/api/v1/me/drive-migration", null, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noReportLineHoldsAUrlKeyOrEmail(CapturedOutput output) {
        UserView owner = newUser("backfill-report");
        awaitSpace(owner.id());
        List<UUID> runs = earlierRuns(owner, 2);
        restoreEntry(runs.get(0));
        jdbc.update("UPDATE run_step SET outputs = '\"not a list\"'::jsonb WHERE run_id = ?", runs.get(1));
        try {
            backfill.run("dry-run", false);
            backfill.run("reconcile", false);
            backfill.run("apply", false);
        } finally {
            jdbc.update("UPDATE run_step SET outputs = NULL WHERE run_id = ?", runs.get(1));
        }

        List<String> lines = output.getOut().lines()
                .filter(line -> line.contains("DriveBackfill") || line.contains("Drive backfill")).toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines).noneMatch(line -> line.contains("://") || line.contains("@") || line.contains("runs/")
                || line.contains("flowcortex-media"));
    }

    /** Spaces off is the default: the route answers 503 and the backfill still works. */
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @AutoConfigureTestRestTemplate
    static class SpacesOff extends AbstractWorkflowHttpIT {

        @Test
        void meDriveMigrationAnswers503WithSpacesOff() {
            UserView owner = newUser("backfill-off");

            ResponseEntity<ProblemView> response = get("/api/v1/me/drive-migration", loginAs(owner),
                    ProblemView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** Completes {@code count} runs and removes their Memory rows, as if they had finished before Drive. */
    private List<UUID> earlierRuns(UserView owner, int count) {
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID run = completedEchoRun(owner, "earlier " + i);
            awaitEntries(run, 1);
            runs.add(run);
        }
        runs.forEach(run -> jdbc.update("DELETE FROM memory_result WHERE run_id = ?", run));
        return runs;
    }

    /** Puts the run's entry back the way the live listener does, without touching any migration record. */
    private void restoreEntry(UUID run) {
        population.populate(run).orElseThrow();
    }

    private long entryCount(List<UUID> runs) {
        return runs.stream().mapToLong(run -> jdbc.queryForObject(
                "SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class, run)).sum();
    }

    private Timestamp migratedAt(UUID space) {
        return jdbc.queryForObject("SELECT migrated_at FROM drive_backfill WHERE space_id = ?", Timestamp.class,
                space);
    }

    private List<Integer> counts(UUID space) {
        Map<String, Object> row = jdbc.queryForMap("SELECT (counts ->> 'runs')::int AS runs, "
                + "(counts ->> 'results')::int AS results FROM drive_backfill WHERE space_id = ?", space);
        return List.of((Integer) row.get("runs"), (Integer) row.get("results"));
    }

    private UUID completedEchoRun(UserView owner, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        RunView run = workflowRunService.startRun(owner.id(), workflow.id(), input);
        awaitTerminal(owner.id(), run.id());
        return run.id();
    }

    private UUID awaitSpace(UUID ownerId) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            List<UUID> spaces = jdbc.queryForList("SELECT id FROM space WHERE owner_id = ? AND is_default",
                    UUID.class, ownerId);
            if (!spaces.isEmpty()) {
                return spaces.get(0);
            }
            assertThat(Instant.now()).as("personal space within %s", TIMEOUT).isBefore(deadline);
            sleep();
        }
    }

    private void awaitEntries(UUID runId, long count) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE run_id = ?", Long.class, runId)
                != count) {
            assertThat(Instant.now()).as("%s entries for run %s within %s", count, runId, TIMEOUT).isBefore(deadline);
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }
}
