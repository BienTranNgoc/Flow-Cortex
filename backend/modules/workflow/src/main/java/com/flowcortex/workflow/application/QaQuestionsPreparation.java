package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.application.QaQuestionGenerator.QaPrompt;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the questions of a QA review opened with model questions on. The listener runs after the
 * opening commits and returns as soon as the call is queued on {@link QaQuestionsPool}, so no
 * thread of the shared async pool waits on the provider. Whatever goes wrong (no generator, a full
 * pool, a failed or unusable answer) ends with the standard questions, so a task never stays
 * {@code generating}; a call lost with its instance is closed by {@link ReviewExpirySweep}.
 * The store is conditional on {@code generating}, so a redelivered event keeps the first answer.
 */
@Component
class QaQuestionsPreparation {

    /** The longest text of the instruction, the run input or the content that reaches the model. */
    static final int MAX_PROMPT_CHARACTERS = 8000;

    private static final Logger LOG = LoggerFactory.getLogger(QaQuestionsPreparation.class);

    private final ReviewTaskRepository reviewTaskRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ObjectProvider<QaQuestionGenerator> generators;
    private final QaQuestionsPool pool;
    private final RunUpdates runUpdates;
    /** Its own plain mapper, like {@link InputResolver}: this module runs on Jackson 2. */
    private final ObjectMapper json = new ObjectMapper();

    QaQuestionsPreparation(ReviewTaskRepository reviewTaskRepository, RunStepRepository runStepRepository,
            WorkflowRunRepository workflowRunRepository, ObjectProvider<QaQuestionGenerator> generators,
            QaQuestionsPool pool, RunUpdates runUpdates) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.generators = generators;
        this.pool = pool;
        this.runUpdates = runUpdates;
    }

    @ApplicationModuleListener
    void on(QaReviewOpenedEvent event) {
        ReviewTask task = reviewTaskRepository.findById(event.taskId()).orElse(null);
        if (task == null || !ReviewTask.QUESTIONS_GENERATING.equals(task.getQuestionsState())) {
            return;
        }
        QaQuestionGenerator generator = generators.getIfAvailable();
        if (generator == null) {
            LOG.warn("No question generator: taskId={} gets the standard questions", task.getId());
            store(task.getId(), task.getRunId(), QaQuestions.STANDARD_MARKER);
            return;
        }
        QaPrompt prompt = prompt(task);
        UUID taskId = task.getId();
        UUID runId = task.getRunId();
        try {
            pool.execute(() -> prepare(taskId, runId, generator, prompt));
        } catch (TaskRejectedException e) {
            LOG.warn("Question workers busy: taskId={} gets the standard questions", taskId);
            store(taskId, runId, QaQuestions.STANDARD_MARKER);
        }
    }

    private void prepare(UUID taskId, UUID runId, QaQuestionGenerator generator, QaPrompt prompt) {
        String questions = QaQuestions.STANDARD_MARKER;
        try {
            questions = generator.questionsJson(prompt)
                    .filter(answer -> QaQuestions.parse(answer).isPresent())
                    .orElse(QaQuestions.STANDARD_MARKER);
        } catch (RuntimeException e) {
            // The message is left out: a provider error can quote the request.
            LOG.warn("Question generator failed: taskId={} error={}", taskId, e.getClass().getSimpleName());
        }
        try {
            store(taskId, runId, questions);
        } catch (RuntimeException e) {
            LOG.error("Could not store the questions: taskId={} — the sweep gives the standard ones", taskId, e);
        }
    }

    private void store(UUID taskId, UUID runId, String questions) {
        if (reviewTaskRepository.storeQuestionsIfGenerating(taskId, questions) == 1) {
            runUpdates.touched(runId);
        }
    }

    /** Only the three texts the model may see, each cut to its first {@link #MAX_PROMPT_CHARACTERS} characters. */
    private QaPrompt prompt(ReviewTask task) {
        String config = runStepRepository.findById(task.getRunStepId()).map(step -> step.getConfig()).orElse(null);
        String runInput = workflowRunRepository.findById(task.getRunId()).map(run -> run.getInput()).orElse(null);
        return new QaPrompt(cut(instruction(config)), cut(runInput), cut(task.getOriginalPayload()));
    }

    private String instruction(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        try {
            JsonNode instruction = json.readTree(config).get("instruction");
            return instruction != null && instruction.isTextual() ? instruction.textValue() : null;
        } catch (JsonProcessingException e) {
            LOG.warn("Unreadable qa.review config, asking the model without an instruction");
            return null;
        }
    }

    private static String cut(String text) {
        if (text == null || text.codePointCount(0, text.length()) <= MAX_PROMPT_CHARACTERS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_PROMPT_CHARACTERS));
    }
}
