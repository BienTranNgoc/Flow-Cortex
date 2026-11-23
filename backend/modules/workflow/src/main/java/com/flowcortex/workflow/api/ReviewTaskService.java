package com.flowcortex.workflow.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's view of and decisions on a run's review tasks. {@code decisionKey} is the trimmed
 * {@code Idempotency-Key} or null; {@code locale} words the standard QA questions and the compiled
 * answers.
 */
public interface ReviewTaskService {

    List<ReviewTaskView> getTasksByRunId(UUID runId, UUID ownerId, Locale locale);

    /**
     * @param pickedOption zero-based index into the task's pick options, or null
     */
    ReviewTaskView approveTask(UUID taskId, UUID reviewerId, String reviewedPayload,
            UUID revisionId, Long expectedVersion, Integer pickedOption, String decisionKey, Locale locale);

    /**
     * Completes a ready QA review with the reviewer's answers (question id to a text or a list of
     * texts); the next step receives the content followed by the answers.
     */
    ReviewTaskView answerTask(UUID taskId, UUID reviewerId, Map<String, Object> answers, String decisionKey,
            Locale locale);

    ScriptRevisionView saveRevision(UUID taskId, UUID reviewerId, String content, long expectedVersion);

    List<ScriptRevisionView> getRevisions(UUID taskId, UUID ownerId);

    ReviewTaskView rejectTask(UUID taskId, UUID reviewerId, String decisionKey, Locale locale);
}
