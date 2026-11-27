package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlotRequest;
import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmSpeechResult;
import com.flowcortex.realtime.api.RealtimeEvents;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import com.flowcortex.workflow.api.WorkflowView;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The model-written reading of a table result, with a stub standing in for the provider. The pool is one thread with
 * one waiting place so a test can fill it; every test waits for its own completion events to be handled, so the next
 * one starts on an idle pool. Runs go through the engine ({@code echo} returns the run input), the nested child run
 * and the repeated event are seeded with SQL and a published event.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.run-insight.enabled=true",
        "flowcortex.workflow.run-insight.pool.size=1",
        "flowcortex.workflow.run-insight.pool.queue-capacity=1"})
@AutoConfigureTestRestTemplate
@Import(RunInsightIT.Stubs.class)
@ExtendWith(OutputCaptureExtension.class)
class RunInsightIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String TABLE = "[{\"city\":\"Hanoi\",\"sales\":120},{\"city\":\"Hue\",\"sales\":80}]";
    private static final String ANSWER = "{\"headline\":\"Hanoi leads\",\"highlights\":[\"Hanoi sold 120\","
            + "\"Hue sold 80\"]}";

    @Autowired
    private FakeLlm stub;

    @Autowired
    private RecordingEvents events;

    @Autowired
    private OffsetClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private ApplicationContext context;

    record InsightJson(String headline, List<String> highlights) {
    }

    record RunJson(UUID id, String status, InsightJson insight, boolean insightPending) {
    }

    record WorkflowJson(UUID id, String name, boolean generateInsight) {
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
        events.reset();
    }

    @AfterEach
    void resetClock() {
        clock.offset = Duration.ZERO;
    }

    @Test
    void theOwnerTurnsInsightOnAndOtherFieldsKeepIt() {
        UserView owner = newUser("run-insight-switch");
        String token = loginAs(owner);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("report", "echo", null));
        assertThat(readWorkflow(workflow.id(), token).generateInsight()).isFalse();

        ResponseEntity<WorkflowJson> on = patchWorkflow(workflow.id(), token, Map.of("generateInsight", true));
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().generateInsight()).isTrue();

        ResponseEntity<WorkflowJson> renamed = patchWorkflow(workflow.id(), token, Map.of("name", "Weekly report"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().name()).isEqualTo("Weekly report");
        assertThat(renamed.getBody().generateInsight()).isTrue();
        assertThat(readWorkflow(workflow.id(), token).generateInsight()).isTrue();
    }

    @Test
    void anotherMemberGetsTheRoutesExistingRefusalAndNothingChanges() {
        UserView owner = newUser("run-insight-owner");
        String token = loginAs(owner);
        String intruder = loginAs(newUser("run-insight-intruder"));
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("report", "echo", null));

        HttpStatusCode refusal = patchWorkflowRaw(workflow.id(), intruder, Map.of("name", "Hijacked")).getStatusCode();
        ResponseEntity<String> attempt = patchWorkflowRaw(workflow.id(), intruder, Map.of("generateInsight", true));

        assertThat(refusal.is4xxClientError()).isTrue();
        assertThat(attempt.getStatusCode()).isEqualTo(refusal);
        assertThat(readWorkflow(workflow.id(), token).generateInsight()).isFalse();
    }

    @Test
    void aTableResultGetsAnInsightAndIsPendingUntilItIsWritten() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub.answer = request -> {
            entered.countDown();
            awaitLatch(release);
            return answered(ANSWER);
        };
        Member member = optedInMember("run-insight-table");
        try {
            UUID runId = completedRun(member, TABLE);

            assertThat(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            RunJson before = read(member, runId);
            assertThat(before.status()).isEqualTo("completed");
            assertThat(before.insight()).isNull();
            assertThat(before.insightPending()).isTrue();

            release.countDown();
            RunJson after = awaitInsight(member, runId);
            assertThat(after.insight().headline()).isEqualTo("Hanoi leads");
            assertThat(after.insight().highlights()).containsExactly("Hanoi sold 120", "Hue sold 80");
            assertThat(after.insightPending()).isFalse();
            assertThat(stub.requests).hasSize(1);
            LlmSlotRequest request = stub.requests.getFirst();
            assertThat(request.slot()).isEqualTo("run-insight");
            assertThat(request.userText()).contains("Hanoi", "Hue");
            assertThat(request.userText()).doesNotContain(runId.toString(), member.workflow().id().toString(),
                    member.user().id().toString());
        } finally {
            release.countDown();
        }
    }

    @Test
    void theRunUpdateSignalIsSentAfterTheInsightIsWritten() {
        stub.answer = request -> answered(ANSWER);
        Member member = optedInMember("run-insight-signal");

        UUID runId = completedRun(member, TABLE);
        awaitInsight(member, runId);

        await().atMost(TIMEOUT).until(() -> events.sentWithInsight("run:" + runId));
    }

    @Test
    void aTextResultOrAWorkflowNotOptedInMakesNoCallAndIsNeverPending() {
        Member text = optedInMember("run-insight-text");
        Member notOptedIn = member("run-insight-off");

        UUID textRun = completedRun(text, "Hello world");
        UUID offRun = completedRun(notOptedIn, TABLE);
        awaitEventsHandled(textRun);
        awaitEventsHandled(offRun);

        assertThat(stub.requests).isEmpty();
        for (Member member : List.of(text, notOptedIn)) {
            UUID runId = member == text ? textRun : offRun;
            RunJson run = read(member, runId);
            assertThat(run.insight()).isNull();
            assertThat(run.insightPending()).isFalse();
        }
    }

    @Test
    void aNestedChildRunMakesNoCall() {
        Member member = optedInMember("run-insight-child");
        UUID parent = seedRun(member, "completed", null, null);
        UUID parentStep = seedStep(parent, 1, "workflow.call", "completed", null);
        UUID child = seedRun(member, "completed", parent, parentStep);
        seedStep(child, 1, "echo", "completed", TABLE);

        complete(member, child);
        awaitEventsHandled(child);

        assertThat(stub.requests).isEmpty();
        RunJson run = read(member, child);
        assertThat(run.insight()).isNull();
        assertThat(run.insightPending()).isFalse();
    }

    @Test
    void aProviderErrorLeavesTheRunCompletedLogsAWarningAndCountsFailed(CapturedOutput output) {
        stub.answer = request -> new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT);
        Member member = optedInMember("run-insight-down");
        double before = outcome("failed");

        UUID runId = completedRun(member, TABLE);

        await().atMost(TIMEOUT).until(() -> outcome("failed") == before + 1);
        RunJson run = read(member, runId);
        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.insight()).isNull();
        List<String> lines = output.getAll().lines().filter(line -> line.contains("Run insight failed")).toList();
        assertThat(lines).anyMatch(line -> line.contains(runId.toString()) && line.contains("kind=TIMEOUT"));
        assertThat(output.getAll()).doesNotContain("Hanoi");
    }

    @Test
    void aBlockedAnswerAndABlankHeadlineWriteNothingAndAreCounted() {
        Member member = optedInMember("run-insight-unusable");
        double blocked = outcome("blocked");
        double unparseable = outcome("unparseable");

        stub.answer = request -> new LlmJsonResult.Blocked("SAFETY");
        UUID blockedRun = completedRun(member, TABLE);
        await().atMost(TIMEOUT).until(() -> outcome("blocked") == blocked + 1);

        stub.answer = request -> answered("{\"headline\":\" \",\"highlights\":[\"x\"]}");
        UUID blankRun = completedRun(member, TABLE);
        await().atMost(TIMEOUT).until(() -> outcome("unparseable") == unparseable + 1);

        assertThat(read(member, blockedRun).insight()).isNull();
        assertThat(read(member, blankRun).insight()).isNull();
        assertThat(read(member, blankRun).status()).isEqualTo("completed");
    }

    @Test
    void aCompletionHeardTwiceCallsTheModelOnceAndKeepsTheInsight() {
        stub.answer = request -> answered(ANSWER);
        Member member = optedInMember("run-insight-twice");
        UUID runId = completedRun(member, TABLE);
        RunJson first = awaitInsight(member, runId);
        awaitEventsHandled(runId);
        stub.answer = request -> answered("{\"headline\":\"Replaced\",\"highlights\":[]}");

        complete(member, runId);
        awaitEventsHandled(runId);

        assertThat(stub.requests).hasSize(1);
        assertThat(read(member, runId).insight()).isEqualTo(first.insight());
    }

    @Test
    void whenTheWorkersAreBusyTheSurplusRunIsSkippedAndTheOthersAreWritten(CapturedOutput output) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub.answer = request -> {
            entered.countDown();
            awaitLatch(release);
            return answered(ANSWER);
        };
        Member member = optedInMember("run-insight-busy");
        double skipped = outcome("skipped");
        try {
            UUID running = completedRun(member, TABLE);
            assertThat(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            UUID queued = completedRun(member, TABLE);
            awaitEventsHandled(queued);
            UUID rejected = completedRun(member, TABLE);

            await().atMost(TIMEOUT).until(() -> outcome("skipped") == skipped + 1);
            awaitEventsHandled(rejected);
            RunJson rejectedRun = read(member, rejected);
            assertThat(rejectedRun.status()).isEqualTo("completed");
            assertThat(rejectedRun.insight()).isNull();
            assertThat(stub.requests).hasSize(1);
            assertThat(output.getAll().lines().filter(line -> line.contains("Run insight workers busy")))
                    .anyMatch(line -> line.contains(rejected.toString()));

            release.countDown();
            awaitInsight(member, running);
            awaitInsight(member, queued);
            assertThat(read(member, rejected).insight()).isNull();
            assertThat(stub.requests).hasSize(2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void pendingEndsTwoMinutesAfterTheRunFinishedWhenTheModelNeverAnswers() {
        stub.answer = request -> new LlmJsonResult.Failed(LlmJsonResult.Kind.UNREACHABLE);
        Member member = optedInMember("run-insight-window");
        double before = outcome("failed");
        UUID runId = completedRun(member, TABLE);
        await().atMost(TIMEOUT).until(() -> outcome("failed") == before + 1);
        assertThat(read(member, runId).insightPending()).isTrue();

        clock.offset = Duration.ofMinutes(2).plusSeconds(1);

        RunJson run = read(member, runId);
        assertThat(run.insight()).isNull();
        assertThat(run.insightPending()).isFalse();
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.workflow.run-insight.enabled=false")
    class SwitchedOff {

        @Autowired
        private FakeLlm nestedStub;

        @Autowired
        private ApplicationContext nestedContext;

        @Test
        void noListenerNoCallAndNothingPending() {
            nestedStub.reset();
            Member member = optedInMember("run-insight-switched-off");

            UUID runId = completedRun(member, TABLE);
            awaitEventsHandled(runId);

            assertThat(nestedContext.containsBean("runInsights")).isFalse();
            assertThat(nestedContext.containsBean("geminiRunInsightWriter")).isFalse();
            assertThat(nestedStub.requests).isEmpty();
            RunJson run = read(member, runId);
            assertThat(run.status()).isEqualTo("completed");
            assertThat(run.insight()).isNull();
            assertThat(run.insightPending()).isFalse();
        }
    }

    @Test
    void theSwitchedOnContextHoldsTheListenerAndTheWriter() {
        assertThat(context.containsBean("runInsights")).isTrue();
        assertThat(context.containsBean("geminiRunInsightWriter")).isTrue();
    }

    private record Member(UserView user, String token, WorkflowView workflow) {
    }

    private Member member(String prefix) {
        UserView user = newUser(prefix);
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("report", "echo", null));
        return new Member(user, loginAs(user), workflow);
    }

    private Member optedInMember(String prefix) {
        Member member = member(prefix);
        assertThat(patchWorkflow(member.workflow().id(), member.token(), Map.of("generateInsight", true))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return member;
    }

    private UUID completedRun(Member member, String input) {
        UUID runId = workflowRunService.startRun(member.user().id(), member.workflow().id(), input).id();
        pollUntil(member.user().id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        return runId;
    }

    private void complete(Member member, UUID runId) {
        transaction.executeWithoutResult(
                status -> publisher.publishEvent(new WorkflowRunCompleted(runId, member.user().id())));
    }

    private RunJson awaitInsight(Member member, UUID runId) {
        await().atMost(TIMEOUT).until(() -> read(member, runId).insight() != null);
        return read(member, runId);
    }

    /** Every listener of the run's completion event has finished, so a call that was going to happen has happened. */
    private void awaitEventsHandled(UUID runId) {
        await().during(Duration.ofMillis(500)).atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE event_type LIKE '%WorkflowRunCompleted' "
                        + "AND serialized_event LIKE ?", Long.class, "%" + runId + "%") == 0);
    }

    private double outcome(String outcome) {
        return meters.counter("workflow.run_insight", "outcome", outcome).count();
    }

    private UUID seedRun(Member member, String status, UUID parentRun, UUID parentStep) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at, "
                + "parent_run_id, parent_step_id, parent_step_attempt, call_depth) "
                + "VALUES (?, ?, ?, ?, 'seed', ?, ?, ?, ?, ?, ?)", id, member.workflow().id(), member.user().id(),
                status, now, now, parentRun, parentStep, parentRun == null ? null : (short) 1,
                parentRun == null ? (short) 0 : (short) 1);
        return id;
    }

    private UUID seedStep(UUID runId, int order, String type, String status, String output) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output) "
                + "VALUES (?, ?, ?, ?, ?, 'seed', ?)", id, runId, order, type, status, output);
        return id;
    }

    private RunJson read(Member member, UUID runId) {
        return get("/api/v1/runs/" + runId, member.token(), RunJson.class).getBody();
    }

    private WorkflowJson readWorkflow(UUID workflowId, String token) {
        return get("/api/v1/workflows/" + workflowId, token, WorkflowJson.class).getBody();
    }

    private ResponseEntity<WorkflowJson> patchWorkflow(UUID workflowId, String token, Map<String, Object> body) {
        return restTemplate.exchange("/api/v1/workflows/" + workflowId, HttpMethod.PATCH, json(token, body),
                WorkflowJson.class);
    }

    private ResponseEntity<String> patchWorkflowRaw(UUID workflowId, String token, Map<String, Object> body) {
        return restTemplate.exchange("/api/v1/workflows/" + workflowId, HttpMethod.PATCH, json(token, body),
                String.class);
    }

    private static HttpEntity<Object> json(String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static LlmJsonResult answered(String json) {
        return new LlmJsonResult.Answered(json, "stub", 1, 1);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stands in for the slot port: records every request and answers with {@link #answer}. */
    static final class FakeLlm implements LlmSlots {

        final List<LlmSlotRequest> requests = new CopyOnWriteArrayList<>();
        volatile Function<LlmSlotRequest, LlmJsonResult> answer;

        FakeLlm() {
            reset();
        }

        void reset() {
            requests.clear();
            answer = request -> answered(ANSWER);
        }

        @Override
        public LlmJsonResult generateJson(LlmSlotRequest request) {
            requests.add(request);
            return answer.apply(request);
        }

        @Override
        public LlmSpeechResult speak(String slot, String text, String voice) {
            throw new UnsupportedOperationException("insight never voices text");
        }

        @Override
        public Duration jsonWorstCase(String slot) {
            return Duration.ofSeconds(18);
        }

        @Override
        public Duration speechWorstCase(String slot) {
            return Duration.ofSeconds(18);
        }

        @Override
        public boolean configured() {
            return true;
        }
    }

    /** Records the run topics the app signals and whether the run's insight was already stored when it did. */
    static final class RecordingEvents implements RealtimeEvents {

        private record Sent(String topic, boolean insightStored) {
        }

        private final JdbcTemplate jdbc;
        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        RecordingEvents(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void reset() {
            sent.clear();
        }

        boolean sentWithInsight(String topic) {
            return sent.stream().anyMatch(entry -> entry.topic().equals(topic) && entry.insightStored());
        }

        @Override
        public void publish(String topic, String type, Object payload) {
            boolean stored = false;
            if (topic.startsWith("run:")) {
                Boolean present = jdbc.queryForObject("SELECT insight IS NOT NULL FROM workflow_run WHERE id = ?::uuid",
                        Boolean.class, topic.substring("run:".length()));
                stored = Boolean.TRUE.equals(present);
            }
            sent.add(new Sent(topic, stored));
        }
    }

    /** The app's clock plus an offset a test sets. */
    static final class OffsetClock extends Clock {

        volatile Duration offset = Duration.ZERO;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed to UTC");
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {

        @Bean
        @Primary
        FakeLlm insightFakeLlm() {
            return new FakeLlm();
        }

        @Bean
        @Primary
        RecordingEvents insightRecordingEvents(JdbcTemplate jdbc) {
            return new RecordingEvents(jdbc);
        }

        @Bean
        @Primary
        OffsetClock insightOffsetClock() {
            return new OffsetClock();
        }
    }
}
