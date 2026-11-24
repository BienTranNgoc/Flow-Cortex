package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.QaQuestionGenerator;
import com.flowcortex.workflow.application.QaQuestionGenerator.QaPrompt;
import com.flowcortex.workflow.application.QaReviewOpenedEvent;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Model-written questions after a QA review opens, with a stub standing in for the provider. The
 * pool is one thread with one waiting place so a test can fill it; every test waits for its own
 * tasks to settle, so the next one starts on an idle pool.
 */
@Testcontainers
@SpringBootTest(properties = {
        "flowcortex.workflow.reaper-interval-ms=3600000",
        "flowcortex.workflow.review.sweep-interval-ms=3600000",
        "flowcortex.workflow.qa-review.ai-questions.enabled=true",
        "flowcortex.workflow.qa-review.pool.size=1",
        "flowcortex.workflow.qa-review.pool.queue-capacity=1"})
class QaQuestionsPreparationIT extends AbstractWorkflowModuleIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = "[{\"id\":\"tone\",\"type\":\"single\",\"question\":\"Which tone?\","
            + "\"options\":[\"Formal\",\"Casual\"]},{\"id\":\"why\",\"type\":\"text\",\"question\":\"Why?\"}]";

    @Autowired
    private StubGenerator stub;

    @Autowired
    private ReviewTaskRepository reviewTasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    @Test
    void validModelAnswerIsStored() throws IOException {
        stub.answer = prompt -> Optional.of(VALID);

        UUID taskId = pausedQaTask("Ask about tone", "Draft post");

        ReviewTask ready = awaitReady(taskId);
        assertThat(JSON.readTree(ready.getQuestions())).isEqualTo(JSON.readTree(VALID));
    }

    @Test
    void invalidModelAnswerFallsBackToStandard() {
        stub.answer = prompt -> Optional.of(sixQuestions());

        UUID taskId = pausedQaTask(null, "Draft post");

        assertStandard(awaitReady(taskId));
    }

    @Test
    void generatorEmptyFallsBackToStandard() {
        stub.answer = prompt -> Optional.empty();

        UUID taskId = pausedQaTask(null, "Draft post");

        assertStandard(awaitReady(taskId));
    }

    @Test
    void aGeneratorThatThrowsFallsBackToStandard() {
        stub.answer = prompt -> {
            throw new IllegalStateException("provider exploded");
        };

        UUID taskId = pausedQaTask(null, "Draft post");

        assertStandard(awaitReady(taskId));
    }

    @Test
    void aRedeliveredEventKeepsTheFirstQuestions() throws IOException {
        stub.answer = prompt -> Optional.of(VALID);
        UUID taskId = pausedQaTask(null, "Draft post");
        awaitReady(taskId);
        stub.answer = prompt -> Optional.of("[{\"id\":\"other\",\"type\":\"text\",\"question\":\"Later?\"}]");

        transaction.executeWithoutResult(status -> publisher.publishEvent(new QaReviewOpenedEvent(taskId)));
        await().during(Duration.ofMillis(500)).atMost(TIMEOUT).until(() -> noPublicationLeft(taskId));

        assertThat(JSON.readTree(reviewTasks.findById(taskId).orElseThrow().getQuestions()))
                .isEqualTo(JSON.readTree(VALID));
    }

    @Test
    void promptCarriesOnlyInstructionInputAndContentCutTo8000() {
        stub.answer = prompt -> Optional.of(VALID);
        String longInput = "x".repeat(9000);

        UUID taskId = pausedQaTask("Ask about tone", longInput);
        awaitReady(taskId);

        QaPrompt prompt = stub.prompts.get(0);
        assertThat(prompt.instruction()).isEqualTo("Ask about tone");
        assertThat(prompt.runInput()).isEqualTo("x".repeat(8000));
        assertThat(prompt.content()).isEqualTo("x".repeat(8000));
        String runId = jdbc.queryForObject("SELECT run_id FROM review_task WHERE id = ?", String.class, taskId);
        String workflowId = jdbc.queryForObject("SELECT workflow_id FROM workflow_run WHERE id = ?::uuid", String.class,
                runId);
        String ownerId = jdbc.queryForObject("SELECT owner_id FROM workflow_run WHERE id = ?::uuid", String.class,
                runId);
        for (String text : List.of(prompt.instruction(), prompt.runInput(), prompt.content())) {
            assertThat(text).doesNotContain(runId, workflowId, ownerId);
        }
    }

    @Test
    void theListenerReturnsWhileTheModelIsStillAnswering() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub.answer = prompt -> {
            entered.countDown();
            awaitLatch(release);
            return Optional.of(VALID);
        };
        try {
            UUID taskId = pausedQaTask(null, "Draft post");

            assertThat(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            await().atMost(TIMEOUT).until(() -> noPublicationLeft(taskId));
            assertThat(reviewTasks.findById(taskId).orElseThrow().getQuestionsState())
                    .isEqualTo(ReviewTask.QUESTIONS_GENERATING);

            release.countDown();
            awaitReady(taskId);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aFullPoolStoresTheStandardQuestionsAtOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub.answer = prompt -> {
            entered.countDown();
            awaitLatch(release);
            return Optional.of(VALID);
        };
        try {
            UUID running = pausedQaTask(null, "one");
            assertThat(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            UUID queued = pausedQaTask(null, "two");
            await().atMost(TIMEOUT).until(() -> noPublicationLeft(queued));
            UUID rejected = pausedQaTask(null, "three");

            assertStandard(awaitReady(rejected));
            assertThat(reviewTasks.findById(running).orElseThrow().getQuestionsState())
                    .isEqualTo(ReviewTask.QUESTIONS_GENERATING);

            release.countDown();
            awaitReady(running);
            awaitReady(queued);
        } finally {
            release.countDown();
        }
    }

    private UUID pausedQaTask(String instruction, String input) {
        UserView owner = newUser("qa-prep");
        String config = instruction == null ? null : "{\"instruction\":\"" + instruction + "\"}";
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("ask", "qa.review", null, config));
        UUID runId = workflowRunService.startRun(owner.id(), workflow.id(), input).id();
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);
        return jdbc.queryForObject("SELECT id FROM review_task WHERE run_id = ?", UUID.class, runId);
    }

    private ReviewTask awaitReady(UUID taskId) {
        await().atMost(TIMEOUT).until(() -> ReviewTask.QUESTIONS_READY
                .equals(reviewTasks.findById(taskId).orElseThrow().getQuestionsState()));
        return reviewTasks.findById(taskId).orElseThrow();
    }

    private boolean noPublicationLeft(UUID taskId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE '%QaReviewOpenedEvent' "
                + "AND serialized_event LIKE ?", Long.class, "%" + taskId + "%") == 0;
    }

    private static void assertStandard(ReviewTask task) {
        try {
            assertThat(JSON.readTree(task.getQuestions())).isEqualTo(JSON.readTree("{\"standard\":true}"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String sixQuestions() {
        StringBuilder six = new StringBuilder("[");
        for (int i = 1; i <= 6; i++) {
            six.append(i > 1 ? "," : "").append("{\"id\":\"q").append(i).append("\",\"type\":\"text\",")
                    .append("\"question\":\"Question ").append(i).append("?\"}");
        }
        return six.append("]").toString();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A provider stand-in: the answer each test sets, and the prompts it was asked. */
    static final class StubGenerator implements QaQuestionGenerator {

        final List<QaPrompt> prompts = new CopyOnWriteArrayList<>();
        volatile Function<QaPrompt, Optional<String>> answer = prompt -> Optional.empty();

        void reset() {
            prompts.clear();
            answer = prompt -> Optional.empty();
        }

        @Override
        public Optional<String> questionsJson(QaPrompt prompt) {
            prompts.add(prompt);
            return answer.apply(prompt);
        }
    }

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StubGenerator stubGenerator() {
            return new StubGenerator();
        }
    }
}
