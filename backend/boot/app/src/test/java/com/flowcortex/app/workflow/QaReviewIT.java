package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.app.chat.ChatIt;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Decisions on a QA review and on a pick list over HTTP: the answers route, the picked option, the
 * time limit and the repeat key. Shares the chat ITs' context (credits on), so every member is funded.
 */
@ChatIt
class QaReviewIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String COMPILED_JUST_ME = "Draft post\n\n---\n## Human review\n\n"
            + "**Who is the audience for this output?**\nJust me\n\n"
            + "**How detailed should the output be?**\n(no answer)\n\n"
            + "**Any specific requirements or constraints? (optional)**\n(no answer)\n\n";

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    protected UserView newUser(String prefix) {
        UserView user = super.newUser(prefix);
        await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, user.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = 1000000 WHERE user_id = ?", user.id());
        return user;
    }

    @Test
    void qaRunPausesWithStandardQuestionsInTheRequestLanguage() {
        UserView owner = newUser("qa-language");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");

        TaskView english = onlyTask(token, runId, "en");
        TaskView vietnamese = onlyTask(token, runId, "vi");

        assertThat(english.reviewType()).isEqualTo("QA_REVIEW");
        assertThat(english.questionsState()).isEqualTo("ready");
        assertThat(english.expiresAt()).isNotNull();
        assertThat(english.questions()).extracting(QuestionView::id).containsExactly("q1", "q2", "q3");
        assertThat(english.questions().get(0).question()).isEqualTo("Who is the audience for this output?");
        assertThat(english.questions().get(0).options()).first().isEqualTo("Just me");
        assertThat(vietnamese.questions().get(0).question()).isEqualTo("Đối tượng nhận kết quả này là ai?");
        assertThat(vietnamese.questions().get(0).options()).hasSize(4).first().isEqualTo("Cá nhân tôi");
    }

    @Test
    void answeringResumesTheRunWithTheCompiledInput() {
        UserView owner = newUser("qa-answer");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");

        ResponseEntity<TaskView> answered = send("/api/v1/review-tasks/" + task.id() + "/answers", token, "en", null,
                Map.of("answers", Map.of("q1", "Just me")), TaskView.class);

        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answered.getBody().status()).isEqualTo("approved");
        assertThat(answered.getBody().answers()).isEqualTo(Map.of("q1", "Just me"));
        RunView finished = pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(2).output()).isEqualTo(COMPILED_JUST_ME);
    }

    @Test
    void approvingAQaTaskAsIsForwardsTheContent() {
        UserView owner = newUser("qa-as-is");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");

        ResponseEntity<TaskView> approved = send("/api/v1/review-tasks/" + task.id() + "/approve", token, "en", null,
                null, TaskView.class);

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        RunView finished = pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(2).output()).isEqualTo("Draft post");
    }

    @Test
    void pickedOptionReachesTheNextStepAsJson() {
        UserView owner = newUser("qa-pick");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "APPROVAL_CHECKPOINT", "[{\"hook\":\"A\"},{\"hook\":\"B\"}]");
        TaskView task = onlyTask(token, runId, "en");
        assertThat(task.pickOptions()).containsExactly(Map.of("hook", "A"), Map.of("hook", "B"));

        ResponseEntity<Problem> outOfRange = send("/api/v1/review-tasks/" + task.id() + "/approve", token, "en", null,
                Map.of("pickedOption", 2), Problem.class);
        assertThat(outOfRange.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(outOfRange.getBody().errors()).containsKey("pickedOption");
        assertThat(onlyTask(token, runId, "en").status()).isEqualTo("pending");

        ResponseEntity<TaskView> picked = send("/api/v1/review-tasks/" + task.id() + "/approve", token, "en", null,
                Map.of("pickedOption", 1), TaskView.class);

        assertThat(picked.getStatusCode()).isEqualTo(HttpStatus.OK);
        RunView finished = pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(2).output()).isEqualTo("{\"hook\":\"B\"}");
    }

    @Test
    void aDoubleApproveWithOneKeyRunsTheNextStepOnce() {
        UserView owner = newUser("qa-double");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");
        String path = "/api/v1/review-tasks/" + task.id() + "/approve";

        ResponseEntity<TaskView> first = send(path, token, "en", "k-1", null, TaskView.class);
        ResponseEntity<TaskView> second = send(path, token, "en", "k-1", null, TaskView.class);
        ResponseEntity<Problem> otherKey = send(path, token, "en", "k-2", null, Problem.class);
        ResponseEntity<Problem> rejectWithTheKey = send("/api/v1/review-tasks/" + task.id() + "/reject", token, "en",
                "k-1", null, Problem.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().status()).isEqualTo("approved");
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(otherKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(otherKey.getBody().detail()).isEqualTo("This step is no longer waiting for review");
        assertThat(rejectWithTheKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        RunView finished = pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps().get(2).output()).isEqualTo("Draft post");
    }

    @Test
    void aKeyIsTrimmedBeforeItIsCompared() {
        UserView owner = newUser("qa-trim");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");
        String path = "/api/v1/review-tasks/" + task.id() + "/approve";

        ResponseEntity<TaskView> first = send(path, token, "en", "  k-1  ", null, TaskView.class);
        ResponseEntity<TaskView> repeat = send(path, token, "en", "k-1", null, TaskView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT decision_key FROM review_task WHERE id = ?", String.class, task.id()))
                .isEqualTo("k-1");
    }

    @Test
    void aDecisionAfterExpiryIs409() {
        UserView owner = newUser("qa-expired");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");
        jdbc.update("UPDATE review_task SET expires_at = now() - interval '1 minute' WHERE id = ?", task.id());

        try {
            for (String route : List.of("approve", "reject", "answers")) {
                Object body = "answers".equals(route) ? Map.of("answers", Map.of()) : null;
                ResponseEntity<Problem> english = send("/api/v1/review-tasks/" + task.id() + "/" + route, token, "en",
                        null, body, Problem.class);
                assertThat(english.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(english.getBody().code()).isEqualTo("REVIEW_EXPIRED");
                assertThat(english.getBody().detail()).isEqualTo("This review expired, so the run stopped.");
            }
            ResponseEntity<Problem> vietnamese = send("/api/v1/review-tasks/" + task.id() + "/approve", token, "vi",
                    null, null, Problem.class);
            assertThat(vietnamese.getBody().detail()).startsWith("Yêu cầu duyệt này đã hết hạn");
            assertThat(jdbc.queryForObject("SELECT status FROM review_task WHERE id = ?", String.class, task.id()))
                    .isEqualTo("pending");
        } finally {
            // The container is shared with the sweep and repository ITs, which read every overdue task.
            jdbc.update("UPDATE review_task SET expires_at = now() + interval '7 days' WHERE id = ?", task.id());
        }
    }

    @Test
    void anotherMembersAnswerIs404() {
        UserView owner = newUser("qa-owner");
        String token = loginAs(owner);
        String strangerToken = loginAs(newUser("qa-stranger"));
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");

        ResponseEntity<Problem> refused = send("/api/v1/review-tasks/" + task.id() + "/answers", strangerToken, "en",
                null, Map.of("answers", Map.of("q1", "Just me")), Problem.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody().detail()).isEqualTo("Review task not found");
        assertThat(onlyTask(token, runId, "en").status()).isEqualTo("pending");
    }

    @Test
    void malformedIdempotencyKeyIs400() {
        UserView owner = newUser("qa-badkey");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");

        for (String route : List.of("approve", "reject", "answers")) {
            Object body = "answers".equals(route) ? Map.of("answers", Map.of()) : null;
            ResponseEntity<Problem> refused = send("/api/v1/review-tasks/" + task.id() + "/" + route, token, "en",
                    "k".repeat(81), body, Problem.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().errors()).containsEntry("Idempotency-Key",
                    "Idempotency-Key, when sent, holds 1-80 characters");
        }
        assertThat(onlyTask(token, runId, "en").status()).isEqualTo("pending");
    }

    @Test
    void answersThatDoNotFitOrComeTooEarlyAreRefusedAndChangeNothing() {
        UserView owner = newUser("qa-refused");
        String token = loginAs(owner);
        UUID runId = startRun(owner, token, "qa.review", "Draft post");
        TaskView task = onlyTask(token, runId, "en");
        String path = "/api/v1/review-tasks/" + task.id() + "/answers";

        ResponseEntity<Problem> notOffered = send(path, token, "en", null,
                Map.of("answers", Map.of("q1", "Everyone")), Problem.class);
        assertThat(notOffered.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notOffered.getBody().errors()).containsOnlyKeys("answers.q1");

        jdbc.update("UPDATE review_task SET questions = NULL, questions_state = 'generating' WHERE id = ?", task.id());
        ResponseEntity<Problem> early = send(path, token, "en", null,
                Map.of("answers", Map.of("q1", "Just me")), Problem.class);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().code()).isEqualTo("REVIEW_QUESTIONS_NOT_READY");
        // The container is shared with the repository and sweep ITs, which read every task still generating.
        jdbc.update("UPDATE review_task SET questions = CAST('{\"standard\":true}' AS jsonb), "
                + "questions_state = 'ready' WHERE id = ?", task.id());

        UUID checkpointRun = startRun(owner, token, "APPROVAL_CHECKPOINT", "hello");
        TaskView plain = onlyTask(token, checkpointRun, "en");
        ResponseEntity<Problem> notQa = send("/api/v1/review-tasks/" + plain.id() + "/answers", token, "en", null,
                Map.of("answers", Map.of()), Problem.class);
        assertThat(notQa.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notQa.getBody().code()).isEqualTo("REVIEW_NOT_QA");
        assertThat(onlyTask(token, runId, "en").status()).isEqualTo("pending");
    }

    /** Starts echo, the human step, echo, and waits until the run pauses at the human step. */
    private UUID startRun(UserView owner, String token, String humanType, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("review", humanType, null),
                new NewWorkflowStep("publish", "echo", null));
        ResponseEntity<RunResponseView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView(input), RunResponseView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);
        return runId;
    }

    private TaskView onlyTask(String token, UUID runId, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        ResponseEntity<TaskView[]> tasks = restTemplate.exchange("/api/v1/runs/" + runId + "/review-tasks",
                HttpMethod.GET, new HttpEntity<>(null, headers), TaskView[].class);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tasks.getBody()).hasSize(1);
        return tasks.getBody()[0];
    }

    private <T> ResponseEntity<T> send(String path, String token, String language, String idempotencyKey, Object body,
            Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    record TaskView(
            UUID id, String reviewType, String status, String reviewedPayload, Instant reviewedAt,
            Instant expiresAt, List<Map<String, Object>> pickOptions, List<QuestionView> questions,
            String questionsState, Map<String, Object> answers) {
    }

    record QuestionView(String id, String type, String question, List<String> options) {
    }

    record Problem(String detail, String code, Map<String, String> errors) {
    }
}
