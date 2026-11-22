package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.ScheduleFire;
import com.flowcortex.workflow.domain.WorkflowSchedule;
import com.flowcortex.workflow.infrastructure.ScheduleFireRepository;
import com.flowcortex.workflow.infrastructure.ScheduleFireRow;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schedule and fire tables, their checks and the single-statement writes of the two repositories,
 * against the real schema. Every test owns fresh users and workflows, so owner-scoped reads see only its rows.
 */
@SpringBootTest
class WorkflowScheduleRepositoryIT extends AbstractWorkflowModuleIT {

    private static final String ZONE = "Asia/Ho_Chi_Minh";

    @Autowired
    private WorkflowScheduleRepository schedules;

    @Autowired
    private ScheduleFireRepository fires;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void activeWithoutNextRunIsRefusedByTheDatabase() {
        UUID owner = newUser("active-no-next").id();
        UUID workflow = workflowOf(owner);

        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", null, null, "08:00", "active", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_status");
        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", null, null, "08:00", "active",
                Instant.now(), Instant.now().plusSeconds(60)))
                .as("an active schedule carries no pause time")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_status");
        assertThat(count(owner, workflow)).isZero();
    }

    @Test
    void pausedWithNextRunIsRefusedByTheDatabase() {
        UUID owner = newUser("paused-next").id();
        UUID workflow = workflowOf(owner);

        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", null, null, "08:00", "paused",
                Instant.now(), Instant.now().plusSeconds(60)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_status");
        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", null, null, "08:00", "paused", null, null))
                .as("a paused schedule carries its pause time")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_status");
        assertThat(count(owner, workflow)).isZero();
    }

    @Test
    void weeklyWithoutDayIsRefusedByTheDatabase() {
        UUID owner = newUser("weekly-no-day").id();
        UUID workflow = workflowOf(owner);
        Instant next = Instant.now().plusSeconds(60);

        assertThatThrownBy(() -> insertRaw(owner, workflow, "weekly", null, null, "08:00", "active", null, next))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_days");
        assertThatThrownBy(() -> insertRaw(owner, workflow, "monthly", null, null, "08:00", "active", null, next))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_days");
        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", (short) 1, null, "08:00", "active", null, next))
                .as("a day the frequency does not use is refused")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workflow_schedule_days");
        assertThatThrownBy(() -> insertRaw(owner, workflow, "monthly", null, (short) 29, "08:00", "active", null,
                next)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(owner, workflow)).isZero();
    }

    @Test
    void offGridTimeIsRefusedByTheDatabase() {
        UUID owner = newUser("off-grid").id();
        UUID workflow = workflowOf(owner);
        Instant next = Instant.now().plusSeconds(60);

        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", null, null, "08:10", "active", null, next))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("local_time");
        assertThatThrownBy(() -> insertRaw(owner, workflow, "daily", null, null, "08:00:30", "active", null, next))
                .as("seconds are off the grid too")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("local_time");
        insertRaw(owner, workflow, "daily", null, null, "23:45", "active", null, next);
        assertThat(count(owner, workflow)).isEqualTo(1);
    }

    @Test
    void twoConcurrentUpsertsLeaveOneRow() throws Exception {
        UUID owner = newUser("concurrent").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<WorkflowSchedule>> bodies = List.of(
                    () -> awaitThen(go, () -> upsert(owner, workflow, "daily", null, null, "08:00", true, now, "first")),
                    () -> awaitThen(go, () -> upsert(owner, workflow, "weekly", (short) 2, null, "21:30", true, now,
                            "second")));
            List<Future<WorkflowSchedule>> pending = bodies.stream().map(pool::submit).toList();
            go.countDown();
            List<WorkflowSchedule> answers = new java.util.ArrayList<>();
            for (Future<WorkflowSchedule> f : pending) {
                answers.add(f.get());
            }

            assertThat(answers).allSatisfy(answer -> assertThat(answer).isNotNull());
            assertThat(count(owner, workflow)).isEqualTo(1);
            WorkflowSchedule stored = schedules.findByOwnerIdAndWorkflowId(owner, workflow).orElseThrow();
            boolean isFirst = "first".equals(stored.getInput()) && "daily".equals(stored.getFrequency())
                    && stored.getDayOfWeek() == null && stored.getLocalTime().equals(LocalTime.of(8, 0));
            boolean isSecond = "second".equals(stored.getInput()) && "weekly".equals(stored.getFrequency())
                    && stored.getDayOfWeek() == 2 && stored.getLocalTime().equals(LocalTime.of(21, 30));
            assertThat(isFirst ^ isSecond).as("the row is exactly one of the two bodies").isTrue();
        } catch (ExecutionException e) {
            throw new AssertionError("a concurrent upsert failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void upsertKeepsTheIdOnReplace() {
        UUID owner = newUser("replace").id();
        UUID workflow = workflowOf(owner);
        Instant created = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant later = created.plus(2, ChronoUnit.DAYS);

        WorkflowSchedule first = upsert(owner, workflow, "weekly", (short) 1, null, "08:00", true, created, "digest");
        WorkflowSchedule replaced = upsert(owner, workflow, "daily", null, null, "21:30", true, later, null);

        assertThat(replaced.getId()).isEqualTo(first.getId());
        assertThat(replaced.getCreatedAt()).isEqualTo(created);
        assertThat(replaced.getUpdatedAt()).isEqualTo(later);
        assertThat(replaced.getFrequency()).isEqualTo("daily");
        assertThat(replaced.getDayOfWeek()).isNull();
        assertThat(replaced.getLocalTime()).isEqualTo(LocalTime.of(21, 30));
        assertThat(replaced.getInput()).isNull();
        assertThat(replaced.getNextRunAt()).isEqualTo(later.plusSeconds(3600));
        assertThat(count(owner, workflow)).isEqualTo(1);
    }

    @Test
    void upsertKeepsTheFirstPauseTimeOfAPausedSchedule() {
        UUID owner = newUser("repause").id();
        UUID workflow = workflowOf(owner);
        Instant t0 = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        WorkflowSchedule paused = upsert(owner, workflow, "daily", null, null, "08:00", false, t0, null);
        WorkflowSchedule pausedAgain = upsert(owner, workflow, "daily", null, null, "09:00", false,
                t0.plusSeconds(600), null);
        WorkflowSchedule active = upsert(owner, workflow, "daily", null, null, "09:00", true, t0.plusSeconds(1200),
                null);
        WorkflowSchedule pausedAfterActive = upsert(owner, workflow, "daily", null, null, "09:00", false,
                t0.plusSeconds(1800), null);

        assertThat(paused.getStatus()).isEqualTo(WorkflowSchedule.PAUSED);
        assertThat(paused.getPausedAt()).isEqualTo(t0);
        assertThat(paused.getNextRunAt()).isNull();
        assertThat(pausedAgain.getPausedAt()).as("a replace keeps the first pause time").isEqualTo(t0);
        assertThat(active.getStatus()).isEqualTo(WorkflowSchedule.ACTIVE);
        assertThat(active.getPausedAt()).isNull();
        assertThat(active.getNextRunAt()).isNotNull();
        assertThat(pausedAfterActive.getPausedAt()).as("a new pause takes the new time").isEqualTo(t0.plusSeconds(1800));
    }

    @Test
    void pauseMovesOnlyAnActiveRow() {
        UUID owner = newUser("pause").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);

        assertThat(schedules.pause(owner, workflow, now.plusSeconds(10))).isEqualTo(1);
        assertThat(schedules.pause(owner, workflow, now.plusSeconds(20))).as("already paused").isZero();

        WorkflowSchedule stored = schedules.findByOwnerIdAndWorkflowId(owner, workflow).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WorkflowSchedule.PAUSED);
        assertThat(stored.getPausedAt()).as("the first pause time stays").isEqualTo(now.plusSeconds(10));
        assertThat(stored.getNextRunAt()).isNull();
        assertThat(schedules.pause(owner, UUID.randomUUID(), now)).as("no schedule").isZero();
    }

    @Test
    void resumeMovesOnlyAPausedRow() {
        UUID owner = newUser("resume").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);
        Instant next = now.plusSeconds(7200);

        assertThat(schedules.resume(owner, workflow, next, now)).as("already active").isZero();
        schedules.pause(owner, workflow, now);
        assertThat(schedules.resume(owner, workflow, next, now.plusSeconds(5))).isEqualTo(1);
        assertThat(schedules.resume(owner, workflow, next.plusSeconds(60), now.plusSeconds(6))).isZero();

        WorkflowSchedule stored = schedules.findByOwnerIdAndWorkflowId(owner, workflow).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WorkflowSchedule.ACTIVE);
        assertThat(stored.getPausedAt()).isNull();
        assertThat(stored.getNextRunAt()).isEqualTo(next);
        assertThat(schedules.resume(owner, UUID.randomUUID(), next, now)).as("no schedule").isZero();
    }

    @Test
    void writesKeyedByOwnerNeverTouchAnotherMembersRow() {
        UUID owner = newUser("mine").id();
        UUID other = newUser("theirs").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule mine = upsert(owner, workflow, "daily", null, null, "08:00", true, now, "mine");

        assertThat(schedules.pause(other, workflow, now)).isZero();
        assertThat(schedules.resume(other, workflow, now.plusSeconds(60), now)).isZero();
        assertThat(schedules.deleteByOwnerIdAndWorkflowId(other, workflow)).isZero();
        assertThat(schedules.findByOwnerIdAndWorkflowId(other, workflow)).isEmpty();
        WorkflowSchedule theirs = upsert(other, workflow, "weekly", (short) 5, null, "10:15", true, now, "theirs");

        assertThat(theirs.getId()).as("a second member on the same workflow gets their own row")
                .isNotEqualTo(mine.getId());
        assertThat(schedules.findByOwnerIdAndWorkflowId(owner, workflow).orElseThrow().getInput()).isEqualTo("mine");
        assertThat(schedules.pause(owner, workflow, now)).isEqualTo(1);
        assertThat(schedules.findByOwnerIdAndWorkflowId(other, workflow).orElseThrow().getStatus())
                .isEqualTo(WorkflowSchedule.ACTIVE);
        assertThat(schedules.deleteByOwnerIdAndWorkflowId(owner, workflow)).isEqualTo(1);
        assertThat(schedules.deleteByOwnerIdAndWorkflowId(owner, workflow)).isZero();
        assertThat(schedules.findByOwnerIdAndWorkflowId(other, workflow)).isPresent();
    }

    @Test
    void deleteKeepsFiresWithScheduleIdNull() {
        UUID owner = newUser("delete").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule schedule = upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);
        UUID fire1 = record(schedule, owner, now);
        UUID fire2 = record(schedule, owner, now.plusSeconds(1));

        assertThat(schedules.deleteByOwnerIdAndWorkflowId(owner, workflow)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_schedule_fire WHERE owner_id = ?",
                Integer.class, owner)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM workflow_schedule_fire WHERE owner_id = ? AND schedule_id IS NULL",
                UUID.class, owner)).containsExactlyInAnyOrder(fire1, fire2);
    }

    @Test
    void recordForADeletedScheduleInsertsNothing() {
        UUID owner = newUser("record-gone").id();
        UUID other = newUser("record-other").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule schedule = upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);

        assertThat(fires.recordForSchedule(UUID.randomUUID(), schedule.getId(), other, ScheduleFire.MANUAL, now,
                "schedule:x", now)).as("another member's schedule id").isZero();
        schedules.deleteByOwnerIdAndWorkflowId(owner, workflow);
        assertThat(fires.recordForSchedule(UUID.randomUUID(), schedule.getId(), owner, ScheduleFire.MANUAL, now,
                "schedule:y", now)).as("a schedule deleted since it was read").isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_schedule_fire WHERE owner_id IN (?, ?)",
                Integer.class, owner, other)).isZero();
    }

    @Test
    void recordedFireCopiesTheScheduleOwnerAndWorkflowAndStartsStarting() {
        UUID owner = newUser("record").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule schedule = upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);
        UUID fireId = UUID.randomUUID();

        assertThat(fires.recordForSchedule(fireId, schedule.getId(), owner, ScheduleFire.MANUAL, now.minusSeconds(5),
                "schedule:" + fireId, now)).isEqualTo(1);

        ScheduleFire fire = fires.findById(fireId).orElseThrow();
        assertThat(fire.getOwnerId()).isEqualTo(owner);
        assertThat(fire.getWorkflowId()).isEqualTo(workflow);
        assertThat(fire.getScheduleId()).isEqualTo(schedule.getId());
        assertThat(fire.getTrigger()).isEqualTo(ScheduleFire.MANUAL);
        assertThat(fire.getStatus()).isEqualTo(ScheduleFire.STARTING);
        assertThat(fire.getScheduledAt()).isEqualTo(now.minusSeconds(5));
        assertThat(fire.getRequestKey()).isEqualTo("schedule:" + fireId);
        assertThat(fires.markStarted(fireId)).isEqualTo(1);
        assertThat(fires.markStarted(fireId)).as("a fire moves once").isZero();
        assertThat(fires.markFailed(fireId, "X", "k", null)).as("a started fire cannot fail").isZero();
    }

    @Test
    void historyJoinFindsTheRunByRequestKeyAndFlagsADeletedRun() {
        UUID owner = newUser("join").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule schedule = upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);
        UUID withRun = record(schedule, owner, now.plusSeconds(1));
        UUID withDeletedRun = record(schedule, owner, now.plusSeconds(2));
        UUID withoutRun = record(schedule, owner, now.plusSeconds(3));
        UUID run = seedRun(workflow, owner, "completed", "schedule:" + withRun);
        UUID deletedRun = seedRun(workflow, owner, "error", "schedule:" + withDeletedRun);
        jdbc.update("UPDATE workflow_run SET deleted_at = ? WHERE id = ?", Timestamp.from(now), deletedRun);

        List<ScheduleFireRow> rows = fires.findHistory(owner, workflow, PageRequest.of(0, 10)).getContent();

        assertThat(rows).extracting(row -> row.fire().getId()).containsExactly(withoutRun, withDeletedRun, withRun);
        ScheduleFireRow none = rows.get(0);
        assertThat(none.runId()).isNull();
        assertThat(none.runStatus()).isNull();
        assertThat(none.runDeletedAt()).isNull();
        ScheduleFireRow deleted = rows.get(1);
        assertThat(deleted.runId()).as("the join has no deleted filter").isEqualTo(deletedRun);
        assertThat(deleted.runStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(deleted.runDeletedAt()).isEqualTo(now);
        ScheduleFireRow live = rows.get(2);
        assertThat(live.runId()).isEqualTo(run);
        assertThat(live.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(live.runDeletedAt()).isNull();
    }

    @Test
    void historyDoesNotJoinAnotherMembersRunWithTheSameKey() {
        UUID owner = newUser("join-own").id();
        UUID other = newUser("join-other").id();
        UUID workflow = workflowOf(owner);
        UUID otherWorkflow = workflowOf(other);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule schedule = upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);
        UUID fire = record(schedule, owner, now);
        seedRun(otherWorkflow, other, "completed", "schedule:" + fire);

        ScheduleFireRow row = fires.findHistory(owner, workflow, PageRequest.of(0, 10)).getContent().get(0);

        assertThat(row.runId()).isNull();
    }

    @Test
    void failedFireStoresItsKeyAndArgs() {
        UUID owner = newUser("failed").id();
        UUID workflow = workflowOf(owner);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule schedule = upsert(owner, workflow, "daily", null, null, "08:00", true, now, null);
        UUID withArgs = record(schedule, owner, now);
        UUID withoutArgs = record(schedule, owner, now.plusSeconds(1));

        assertThat(fires.markFailed(withArgs, "INSUFFICIENT_CREDITS", "credits.insufficient", "[\"video\", 12]"))
                .isEqualTo(1);
        assertThat(fires.markFailed(withoutArgs, "startError", "workflow.schedule.startFailed", null)).isEqualTo(1);
        assertThat(fires.markFailed(withArgs, "OTHER", "other.key", null)).as("a failed fire stays as it was").isZero();

        ScheduleFire stored = fires.findById(withArgs).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ScheduleFire.FAILED);
        assertThat(stored.getErrorCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(stored.getErrorKey()).isEqualTo("credits.insufficient");
        assertThat(stored.getErrorArgs()).isEqualToIgnoringWhitespace("[\"video\",12]");
        assertThat(jdbc.queryForObject("SELECT error_args->>0 FROM workflow_schedule_fire WHERE id = ?", String.class,
                withArgs)).isEqualTo("video");
        ScheduleFire plain = fires.findById(withoutArgs).orElseThrow();
        assertThat(plain.getErrorKey()).isEqualTo("workflow.schedule.startFailed");
        assertThat(plain.getErrorArgs()).isNull();
    }

    @Test
    void historyIsNewestFirstAndOwnOnly() {
        UUID owner = newUser("history").id();
        UUID other = newUser("history-other").id();
        UUID workflow = workflowOf(owner);
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        WorkflowSchedule mine = upsert(owner, workflow, "daily", null, null, "08:00", true, base, null);
        WorkflowSchedule theirs = upsert(other, workflow, "daily", null, null, "08:00", true, base, null);
        UUID first = record(mine, owner, base.plusSeconds(10));
        UUID second = record(mine, owner, base.plusSeconds(20));
        record(theirs, other, base.plusSeconds(15));
        UUID third = record(mine, owner, base.plusSeconds(30));

        var page = fires.findHistory(owner, workflow, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(row -> row.fire().getId()).containsExactly(third, second);
        assertThat(fires.findHistory(owner, workflow, PageRequest.of(1, 2)).getContent())
                .extracting(row -> row.fire().getId()).containsExactly(first);
        assertThat(fires.findHistory(owner, UUID.randomUUID(), PageRequest.of(0, 2)).getContent()).isEmpty();
    }

    private UUID workflowOf(UUID ownerId) {
        return newWorkflow(ownerId, false, new NewWorkflowStep("only", "echo", null)).id();
    }

    private WorkflowSchedule upsert(UUID owner, UUID workflow, String frequency, Short dayOfWeek, Short dayOfMonth,
            String time, boolean active, Instant now, String input) {
        return schedules.upsert(UUID.randomUUID(), owner, workflow, frequency, dayOfWeek, dayOfMonth,
                LocalTime.parse(time), ZONE, active, active ? now.plusSeconds(3600) : null, input, false, now);
    }

    private UUID record(WorkflowSchedule schedule, UUID owner, Instant at) {
        UUID id = UUID.randomUUID();
        assertThat(fires.recordForSchedule(id, schedule.getId(), owner, ScheduleFire.MANUAL, at, "schedule:" + id, at))
                .isEqualTo(1);
        return id;
    }

    private int count(UUID owner, UUID workflow) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_schedule WHERE owner_id = ? AND workflow_id = ?",
                Integer.class, owner, workflow);
    }

    private void insertRaw(UUID owner, UUID workflow, String frequency, Short dayOfWeek, Short dayOfMonth,
            String time, String status, Instant pausedAt, Instant nextRunAt) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO workflow_schedule (id, owner_id, workflow_id, frequency, day_of_week, day_of_month, "
                        + "local_time, time_zone, status, paused_at, next_run_at, acknowledge_cost, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS time), ?, ?, ?, ?, false, ?, ?)",
                UUID.randomUUID(), owner, workflow, frequency, dayOfWeek, dayOfMonth, time, ZONE, status,
                pausedAt == null ? null : Timestamp.from(pausedAt), nextRunAt == null ? null : Timestamp.from(nextRunAt),
                Timestamp.from(now), Timestamp.from(now));
    }

    private UUID seedRun(UUID workflowId, UUID ownerId, String status, String requestKey) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, request_key) "
                        + "VALUES (?, ?, ?, ?, 'seed', ?, ?)",
                runId, workflowId, ownerId, status, Timestamp.from(Instant.now()), requestKey);
        return runId;
    }

    private static <T> T awaitThen(CountDownLatch go, Callable<T> body) throws Exception {
        go.await();
        return body.call();
    }
}
