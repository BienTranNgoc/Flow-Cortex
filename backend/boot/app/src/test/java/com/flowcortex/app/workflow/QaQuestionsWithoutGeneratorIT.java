package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.application.QaReviewOpenedEvent;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The switch is off, so no generator bean exists; a task that was left {@code generating} (the
 * switch turned off after it opened) still ends with the standard questions when its event arrives.
 */
@Testcontainers
@SpringBootTest(properties = {
        "flowcortex.workflow.reaper-interval-ms=3600000",
        "flowcortex.workflow.review.sweep-interval-ms=3600000"})
class QaQuestionsWithoutGeneratorIT extends AbstractWorkflowModuleIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ReviewTaskRepository reviewTasks;

    @Autowired
    private WorkflowRepository workflows;

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private RunStepRepository steps;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID taskId;

    @AfterEach
    void deleteTask() {
        if (taskId != null) {
            jdbc.update("DELETE FROM review_task WHERE id = ?", taskId);
        }
    }

    @Test
    void withoutAGeneratorBeanTheTaskBecomesReadyWithStandard() throws IOException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Workflow workflow = workflows.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        WorkflowRun run = runs.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", now));
        RunStep step = steps.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "qa.review"));
        taskId = UUID.randomUUID();
        reviewTasks.saveAndFlush(new ReviewTask(taskId, run.getId(), step.getId(), "QA_REVIEW", "payload", now,
                now.plus(Duration.ofDays(7)), ReviewTask.QUESTIONS_GENERATING));

        transaction.executeWithoutResult(status -> publisher.publishEvent(new QaReviewOpenedEvent(taskId)));

        await().atMost(Duration.ofSeconds(10)).until(() -> ReviewTask.QUESTIONS_READY
                .equals(reviewTasks.findById(taskId).orElseThrow().getQuestionsState()));
        assertThat(JSON.readTree(reviewTasks.findById(taskId).orElseThrow().getQuestions()))
                .isEqualTo(JSON.readTree("{\"standard\":true}"));
    }
}
