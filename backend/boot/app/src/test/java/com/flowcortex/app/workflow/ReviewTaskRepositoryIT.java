package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.ReviewTaskStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The review task's conditional writes and its schema constraints, on a real Postgres. Every task
 * a test creates is deleted afterwards: the database is shared with other ITs and the overdue
 * read looks at all pending tasks, not only the ones a test made.
 */
@Testcontainers
@SpringBootTest(properties = {
        "flowcortex.workflow.reaper-interval-ms=3600000",
        "flowcortex.workflow.review.sweep-interval-ms=3600000"})
class ReviewTaskRepositoryIT extends AbstractWorkflowModuleIT {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUESTIONS = "[{\"id\":\"q1\",\"type\":\"text\",\"question\":\"Why?\"}]";

    @Autowired
    private ReviewTaskRepository reviewTasks;

    @Autowired
    private WorkflowRepository workflows;

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private RunStepRepository steps;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedTasks() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM review_task WHERE id = ?", id);
        }
        created.clear();
    }

    @Test
    void expireEndsOnlyAPendingTask() {
        ReviewTask pending = save("APPROVAL_CHECKPOINT", NOW, NOW.plusSeconds(60), null);
        ReviewTask approved = save("APPROVAL_CHECKPOINT", NOW, NOW.plusSeconds(60), null);
        approved.approve(UUID.randomUUID(), "x", NOW);
        reviewTasks.saveAndFlush(approved);

        assertThat(reviewTasks.expireIfPending(pending.getId())).isOne();
        assertThat(reviewTasks.expireIfPending(pending.getId())).isZero();
        assertThat(reviewTasks.expireIfPending(approved.getId())).isZero();

        assertThat(statusOf(pending)).isEqualTo("expired");
        assertThat(statusOf(approved)).isEqualTo("approved");
        assertThat(reviewTasks.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(ReviewTaskStatus.EXPIRED);
    }

    @Test
    void overdueReadIsOldestFirstAndCappedAtTheLimit() {
        ReviewTask newest = save("APPROVAL_CHECKPOINT", NOW.minus(Duration.ofDays(9)), NOW.minusSeconds(10), null);
        ReviewTask oldest = save("APPROVAL_CHECKPOINT", NOW.minus(Duration.ofDays(9)), NOW.minusSeconds(30), null);
        ReviewTask middle = save("APPROVAL_CHECKPOINT", NOW.minus(Duration.ofDays(9)), NOW.minusSeconds(20), null);
        ReviewTask notDue = save("APPROVAL_CHECKPOINT", NOW, NOW.plusSeconds(3600), null);
        ReviewTask decided = save("APPROVAL_CHECKPOINT", NOW.minus(Duration.ofDays(9)), NOW.minusSeconds(40), null);
        decided.reject(UUID.randomUUID(), NOW);
        reviewTasks.saveAndFlush(decided);

        assertThat(reviewTasks.findOverdueIds(NOW, PageRequest.of(0, 100)))
                .containsExactly(oldest.getId(), middle.getId(), newest.getId())
                .doesNotContain(notDue.getId(), decided.getId());
        assertThat(reviewTasks.findOverdueIds(NOW, PageRequest.of(0, 2)))
                .containsExactly(oldest.getId(), middle.getId());
    }

    @Test
    void storeQuestionsWritesOnlyWhileGenerating() throws IOException {
        ReviewTask generating = save("QA_REVIEW", NOW, NOW.plusSeconds(60), ReviewTask.QUESTIONS_GENERATING);
        String other = "[{\"id\":\"q9\",\"type\":\"text\",\"question\":\"Other?\"}]";

        assertThat(reviewTasks.storeQuestionsIfGenerating(generating.getId(), QUESTIONS)).isOne();
        assertThat(reviewTasks.storeQuestionsIfGenerating(generating.getId(), other)).isZero();

        ReviewTask stored = reviewTasks.findById(generating.getId()).orElseThrow();
        assertThat(stored.getQuestionsState()).isEqualTo(ReviewTask.QUESTIONS_READY);
        assertThat(JSON.readTree(stored.getQuestions())).isEqualTo(JSON.readTree(QUESTIONS));
    }

    @Test
    void aQaTaskWithoutQuestionsStateViolatesTheCheck() {
        assertThatThrownBy(() -> save("QA_REVIEW", NOW, NOW.plusSeconds(60), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> save("APPROVAL_CHECKPOINT", NOW, NOW.plusSeconds(60), ReviewTask.QUESTIONS_READY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void qaReviewHasAFreePricingRow() {
        assertThat(jdbc.queryForObject(
                "SELECT estimator_kind FROM capability_pricing WHERE capability = 'qa.review'", String.class))
                .isEqualTo("free");
    }

    @Test
    void anInsertThatNamesNoExpiryGetsSevenDaysFromNow() {
        RunStep step = newStep("APPROVAL_CHECKPOINT");
        UUID id = UUID.randomUUID();
        created.add(id);

        jdbc.update("INSERT INTO review_task (id, run_id, run_step_id, review_type) VALUES (?, ?, ?, ?)",
                id, step.getRunId(), step.getId(), "APPROVAL_CHECKPOINT");

        Instant expiresAt = reviewTasks.findById(id).orElseThrow().getExpiresAt();
        assertThat(expiresAt).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));
    }

    @Test
    void aConditionalUpdateMovesTheVersionSoAStaleSaveFails() {
        ReviewTask forExpiry = save("APPROVAL_CHECKPOINT", NOW, NOW.plusSeconds(60), null);
        ReviewTask staleForExpiry = reviewTasks.findById(forExpiry.getId()).orElseThrow();
        assertThat(reviewTasks.expireIfPending(forExpiry.getId())).isOne();

        staleForExpiry.approve(UUID.randomUUID(), "late", NOW);
        assertThatThrownBy(() -> reviewTasks.saveAndFlush(staleForExpiry))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(statusOf(forExpiry)).isEqualTo("expired");

        ReviewTask forQuestions = save("QA_REVIEW", NOW, NOW.plusSeconds(60), ReviewTask.QUESTIONS_GENERATING);
        ReviewTask staleForQuestions = reviewTasks.findById(forQuestions.getId()).orElseThrow();
        assertThat(reviewTasks.storeQuestionsIfGenerating(forQuestions.getId(), QUESTIONS)).isOne();

        staleForQuestions.approve(UUID.randomUUID(), "early", NOW);
        assertThatThrownBy(() -> reviewTasks.saveAndFlush(staleForQuestions))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        ReviewTask survivor = reviewTasks.findById(forQuestions.getId()).orElseThrow();
        assertThat(survivor.getQuestionsState()).isEqualTo(ReviewTask.QUESTIONS_READY);
        assertThat(survivor.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
    }

    @Test
    void staleGeneratingReadIsByCreationTime() {
        Instant cutoff = NOW.minusSeconds(120);
        ReviewTask newerStale = save("QA_REVIEW", NOW.minusSeconds(200), NOW.plusSeconds(60),
                ReviewTask.QUESTIONS_GENERATING);
        ReviewTask olderStale = save("QA_REVIEW", NOW.minusSeconds(300), NOW.plusSeconds(60),
                ReviewTask.QUESTIONS_GENERATING);
        ReviewTask fresh = save("QA_REVIEW", NOW.minusSeconds(30), NOW.plusSeconds(60),
                ReviewTask.QUESTIONS_GENERATING);
        ReviewTask ready = save("QA_REVIEW", NOW.minusSeconds(400), NOW.plusSeconds(60), ReviewTask.QUESTIONS_READY);

        assertThat(reviewTasks.findStaleGeneratingIds(cutoff, PageRequest.of(0, 100)))
                .containsExactly(olderStale.getId(), newerStale.getId())
                .doesNotContain(fresh.getId(), ready.getId());
        assertThat(reviewTasks.findStaleGeneratingIds(cutoff, PageRequest.of(0, 1)))
                .containsExactly(olderStale.getId());
    }

    private RunStep newStep(String reviewType) {
        Workflow workflow = workflows.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        WorkflowRun run = runs.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", NOW));
        return steps.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 1, reviewType));
    }

    private ReviewTask save(String reviewType, Instant createdAt, Instant expiresAt, String questionsState) {
        RunStep step = newStep(reviewType);
        UUID id = UUID.randomUUID();
        created.add(id);
        return reviewTasks.saveAndFlush(new ReviewTask(id, step.getRunId(), step.getId(), reviewType, "payload",
                createdAt, expiresAt, questionsState));
    }

    private String statusOf(ReviewTask task) {
        return jdbc.queryForObject("SELECT status FROM review_task WHERE id = ?", String.class, task.getId());
    }
}
