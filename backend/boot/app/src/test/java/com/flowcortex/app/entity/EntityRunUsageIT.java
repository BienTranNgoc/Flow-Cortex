package com.flowcortex.app.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.entity.api.EntityRunUsage;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.RunRecords;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The run-usage port and the run history route with spaces on: what a recording keeps, who may be recorded for
 * which run, and how a usage row is stamped completed whichever of the recording and the run-completed signal
 * commits first.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class EntityRunUsageIT extends AbstractWorkflowHttpIT {

    private static final String ENTITIES = "/api/v1/entities";
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionSpaces sessionSpaces;

    @Autowired
    private EntityRunUsage usage;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoSpyBean
    private RunRecords runRecords;

    private final ExecutorService otherThread = Executors.newSingleThreadExecutor();

    record EntityView(UUID id, String name, int version) {
    }

    record RunItem(UUID runId, UUID workflowId, String workflowName, int version, Instant completedAt) {
    }

    record RunPage(List<RunItem> items, int page, int size, long total) {
    }

    private record Member(UUID id, String token, UUID space) {
    }

    @AfterEach
    void stopOtherThread() {
        otherThread.shutdownNow();
    }

    @Test
    void recordsCurrentVersionAndWorkflowName() {
        Member a = member("usage-record");
        EntityView entity = create(a, "Nodi");
        patchName(a, entity.id(), 1, "Nodi 2");
        patchName(a, entity.id(), 2, "Nodi 3");
        UUID other = create(a, "Other").id();
        UUID workflow = workflow(a, "Product video");
        UUID run = run(a, workflow, "running", null);

        int recorded = usage.record(run, a.id(), List.of(entity.id(), other));

        assertThat(recorded).isEqualTo(2);
        Map<String, Object> row = usageRow(run, entity.id());
        assertThat(row).containsEntry("version", 3).containsEntry("workflow_id", workflow)
                .containsEntry("workflow_name", "Product video").containsEntry("space_id", a.space());
        assertThat(row.get("completed_at")).isNull();
        assertThat(usageRow(run, other)).containsEntry("version", 1);
    }

    @Test
    void anotherMembersEntityIsIgnored() {
        Member a = member("usage-own");
        Member b = member("usage-foreign");
        UUID own = create(a, "Mine").id();
        UUID foreign = create(b, "Theirs").id();
        UUID run = run(a, workflow(a, "Product video"), "running", null);

        assertThat(usage.record(run, a.id(), List.of(foreign))).isZero();
        assertThat(usage.record(run, a.id(), List.of(own, foreign, UUID.randomUUID()))).isEqualTo(1);

        assertThat(usageRows(run)).containsExactly(own);
    }

    @Test
    void aTrashedEntityIsIgnored() {
        Member a = member("usage-trashed");
        UUID entity = create(a, "Gone").id();
        assertThat(send(HttpMethod.DELETE, ENTITIES + "/" + entity, a, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        UUID run = run(a, workflow(a, "Product video"), "running", null);

        assertThat(usage.record(run, a.id(), List.of(entity))).isZero();

        assertThat(usageRows(run)).isEmpty();
    }

    @Test
    void recordingTwiceKeepsTheFirstVersion() {
        Member a = member("usage-twice");
        EntityView entity = create(a, "Nodi");
        UUID run = run(a, workflow(a, "Product video"), "running", null);
        assertThat(usage.record(run, a.id(), List.of(entity.id()))).isEqualTo(1);
        patchName(a, entity.id(), 1, "Nodi renamed");

        assertThat(usage.record(run, a.id(), List.of(entity.id()))).isZero();

        assertThat(usageRows(run)).containsExactly(entity.id());
        assertThat(usageRow(run, entity.id())).containsEntry("version", 1);
    }

    @Test
    void aRunOfAnotherOwnerIsRefusedAndNothingRecorded() {
        Member a = member("usage-owner-a");
        Member b = member("usage-owner-b");
        UUID entity = create(a, "Nodi").id();
        UUID foreignRun = run(b, workflow(b, "Product video"), "running", null);

        assertThatThrownBy(() -> usage.record(foreignRun, a.id(), List.of(entity)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usage.record(UUID.randomUUID(), a.id(), List.of(entity)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usage.record(foreignRun, a.id(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_run WHERE entity_id = ?", Long.class,
                entity)).isZero();
    }

    @Test
    void completionStampsTheRunsFinishTime() {
        Member a = member("usage-complete");
        UUID first = create(a, "One").id();
        UUID second = create(a, "Two").id();
        UUID run = run(a, workflow(a, "Product video"), "running", null);
        usage.record(run, a.id(), List.of(first, second));
        Instant finished = complete(run);

        publishCompleted(run, a);

        await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(completedAt(run, first)).isEqualTo(finished);
            assertThat(completedAt(run, second)).isEqualTo(finished);
        });
    }

    @Test
    void recordingAfterCompletionStampsAtOnce() {
        Member a = member("usage-late");
        UUID entity = create(a, "Nodi").id();
        Instant finished = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        UUID run = run(a, workflow(a, "Product video"), "completed", finished);

        assertThat(usage.record(run, a.id(), List.of(entity))).isEqualTo(1);

        assertThat(completedAt(run, entity)).isEqualTo(finished);
    }

    @Test
    void completionCommittedWhileRecordingIsOpenIsStamped() {
        Member a = member("usage-window");
        UUID entity = create(a, "Nodi").id();
        UUID run = run(a, workflow(a, "Product video"), "running", null);
        AtomicReference<Instant> finished = new AtomicReference<>();
        AtomicReference<Runnable> afterRead = new AtomicReference<>(() -> {
            try {
                otherThread.submit(() -> {
                    finished.set(complete(run));
                    publishCompleted(run, a);
                }).get();
                await().atMost(PATIENCE).until(() -> listenerDone(run));
            } catch (Exception e) {
                throw new AssertionError("the run could not be completed from another thread", e);
            }
        });
        doAnswer(call -> {
            Object found = call.callRealMethod();
            Runnable hook = afterRead.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return found;
        }).when(runRecords).find(any());

        Integer recorded = transactions.execute(status -> usage.record(run, a.id(), List.of(entity)));

        assertThat(recorded).isEqualTo(1);
        assertThat(afterRead.get()).isNull();
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(completedAt(run, entity)).isEqualTo(finished.get()));
    }

    @Test
    void aSecondCompletionSignalKeepsTheFirstTime() {
        Member a = member("usage-signal-twice");
        UUID entity = create(a, "Nodi").id();
        UUID run = run(a, workflow(a, "Product video"), "running", null);
        usage.record(run, a.id(), List.of(entity));
        Instant first = complete(run);
        publishCompleted(run, a);
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(completedAt(run, entity)).isEqualTo(first));

        jdbc.update("UPDATE workflow_run SET finished_at = ? WHERE id = ?",
                Timestamp.from(first.plusSeconds(300)), run);
        publishCompleted(run, a);
        await().atMost(PATIENCE).until(() -> listenerDone(run));

        assertThat(completedAt(run, entity)).isEqualTo(first);
    }

    @Test
    void historyListsOnlyCompletedRunsNewestFirst() {
        Member a = member("usage-history");
        EntityView entity = create(a, "Nodi");
        UUID workflow = workflow(a, "Product video");
        Instant earlier = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        Instant later = earlier.plusSeconds(300);
        UUID older = run(a, workflow, "completed", earlier);
        UUID newer = run(a, workflow, "completed", later);
        UUID running = run(a, workflow, "running", null);
        UUID failed = run(a, workflow, "error", later.plusSeconds(60));
        for (UUID run : List.of(older, running, failed, newer)) {
            usage.record(run, a.id(), List.of(entity.id()));
        }

        RunPage all = runs(a, entity.id(), "");
        RunPage second = runs(a, entity.id(), "?page=1&size=1");

        assertThat(all.items()).extracting(RunItem::runId).containsExactly(newer, older);
        assertThat(all.items().get(0)).isEqualTo(new RunItem(newer, workflow, "Product video", 1, later));
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.size()).isEqualTo(20);
        assertThat(second.items()).extracting(RunItem::runId).containsExactly(older);
        assertThat(second.total()).isEqualTo(2);
    }

    @Test
    void historyRefusesWhatItCannotRead() {
        Member a = member("usage-history-a");
        Member b = member("usage-history-b");
        EntityView entity = create(a, "Nodi");
        UUID trashed = create(a, "Gone").id();
        send(HttpMethod.DELETE, ENTITIES + "/" + trashed, a, String.class);

        assertThat(status(b, ENTITIES + "/" + entity.id() + "/runs")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(a, ENTITIES + "/" + trashed + "/runs")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(a, ENTITIES + "/not-a-uuid/runs")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(a, ENTITIES + "/" + UUID.randomUUID() + "/runs")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(a, ENTITIES + "/" + entity.id() + "/runs?size=0")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(a, ENTITIES + "/" + entity.id() + "/runs?size=51")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(a, ENTITIES + "/" + entity.id() + "/runs?page=-1")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(runs(a, entity.id(), "?size=50").items()).isEmpty();
    }

    private Member member(String prefix) {
        UserView user = newUser(prefix);
        return new Member(user.id(), loginAs(user), sessionSpaces.sessionSpace(user.id()).spaceId());
    }

    private EntityView create(Member member, String name) {
        ResponseEntity<EntityView> created = restTemplate.exchange(ENTITIES, HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "brand", "name", name), bearer(member)), EntityView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private void patchName(Member member, UUID id, int baseVersion, String name) {
        ResponseEntity<EntityView> patched = restTemplate.exchange(ENTITIES + "/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("baseVersion", baseVersion, "name", name), bearer(member)), EntityView.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().version()).isEqualTo(baseVersion + 1);
    }

    private <T> ResponseEntity<T> send(HttpMethod method, String path, Member member, Class<T> type) {
        return restTemplate.exchange(path, method, new HttpEntity<>(null, bearer(member)), type);
    }

    private HttpStatus status(Member member, String path) {
        return HttpStatus.valueOf(send(HttpMethod.GET, path, member, String.class).getStatusCode().value());
    }

    private RunPage runs(Member member, UUID entity, String query) {
        ResponseEntity<RunPage> page = send(HttpMethod.GET, ENTITIES + "/" + entity + "/runs" + query, member,
                RunPage.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return page.getBody();
    }

    private static HttpHeaders bearer(Member member) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        return headers;
    }

    private UUID workflow(Member owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, ?, false, now(), now())", id, owner.id(), name);
        return id;
    }

    private UUID run(Member owner, UUID workflow, String status, Instant finishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, 'usage input', now(), ?)", id, workflow, owner.id(), status,
                finishedAt == null ? null : Timestamp.from(finishedAt));
        return id;
    }

    /** Marks the run completed the way the engine does and returns its finish time. */
    private Instant complete(UUID run) {
        Instant finished = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE workflow_run SET status = 'completed', finished_at = ? WHERE id = ?",
                Timestamp.from(finished), run);
        return finished;
    }

    private void publishCompleted(UUID run, Member owner) {
        transactions.executeWithoutResult(status -> events.publishEvent(new WorkflowRunCompleted(run, owner.id())));
    }

    private Map<String, Object> usageRow(UUID run, UUID entity) {
        return jdbc.queryForMap("SELECT * FROM space_entity_run WHERE run_id = ? AND entity_id = ?", run, entity);
    }

    private List<UUID> usageRows(UUID run) {
        return jdbc.queryForList("SELECT entity_id FROM space_entity_run WHERE run_id = ?", UUID.class, run);
    }

    private Instant completedAt(UUID run, UUID entity) {
        Timestamp stamp = jdbc.queryForObject(
                "SELECT completed_at FROM space_entity_run WHERE run_id = ? AND entity_id = ?", Timestamp.class, run,
                entity);
        return stamp == null ? null : stamp.toInstant();
    }

    /** Completed publications are deleted, so none left for the run means the listener finished its delivery. */
    private boolean listenerDone(UUID run) {
        Long pending = jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE listener_id LIKE ? "
                + "AND serialized_event LIKE ?", Long.class, "%EntityRunCompletionListener%", "%" + run + "%");
        return pending == 0;
    }
}
