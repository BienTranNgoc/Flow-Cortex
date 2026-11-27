package com.flowcortex.workflow.application;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the insight of a completed run whose result is a table, for a workflow that asked for it. A bean only while
 * {@code flowcortex.workflow.run-insight.enabled} is true. The listener runs after the completion commits, does only
 * its cheap checks and returns as soon as the call is queued on {@link RunInsightPool}, so no thread of the shared
 * async pool and no database connection waits on the provider and a burst of table runs cannot delay the other
 * completion listeners. Whatever goes wrong ends with no insight and the run untouched: it is logged at WARN with
 * the run id and the kind, never the table or the answer, and counted by outcome. The store is conditional on there
 * being no insight, so a redelivered event keeps the first answer.
 */
@Component
@ConditionalOnProperty(name = "flowcortex.workflow.run-insight.enabled", havingValue = "true")
class RunInsights {

    static final String METER = "workflow.run_insight";

    private static final Logger LOG = LoggerFactory.getLogger(RunInsights.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRepository workflowRepository;
    private final RunStepRepository runStepRepository;
    private final GeminiRunInsightWriter writer;
    private final RunInsightPool pool;
    private final RunUpdates runUpdates;
    private final MeterRegistry meters;

    RunInsights(WorkflowRunRepository workflowRunRepository, WorkflowRepository workflowRepository,
            RunStepRepository runStepRepository, GeminiRunInsightWriter writer, RunInsightPool pool,
            RunUpdates runUpdates, MeterRegistry meters) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowRepository = workflowRepository;
        this.runStepRepository = runStepRepository;
        this.writer = writer;
        this.pool = pool;
        this.runUpdates = runUpdates;
        this.meters = meters;
    }

    @ApplicationModuleListener
    void on(WorkflowRunCompleted event) {
        UUID runId = event.runId();
        Optional<String> prompt = promptFor(runId);
        if (prompt.isEmpty()) {
            return;
        }
        try {
            pool.execute(() -> write(runId, prompt.get()));
        } catch (TaskRejectedException e) {
            LOG.warn("Run insight workers busy: runId={} gets no insight", runId);
            count("skipped");
        }
    }

    /** The prompt when the run is one to write for; empty for every skip of the cheap checks. */
    private Optional<String> promptFor(UUID runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null || run.getDeletedAt() != null || run.getParentRunId() != null || run.getInsight() != null) {
            return Optional.empty();
        }
        boolean optedIn = workflowRepository.findById(run.getWorkflowId()).map(Workflow::isGenerateInsight)
                .orElse(false);
        if (!optedIn) {
            return Optional.empty();
        }
        return RunInsightRules.resultTable(runStepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .map(InsightAnswer::prompt);
    }

    private void write(UUID runId, String prompt) {
        try {
            switch (writer.answer(prompt)) {
                case LlmJsonResult.Answered answered -> store(runId, answered.json());
                case LlmJsonResult.Blocked blocked -> {
                    LOG.warn("Run insight blocked by the provider: runId={} reason={}", runId, blocked.reason());
                    count("blocked");
                }
                case LlmJsonResult.Failed failed -> {
                    LOG.warn("Run insight failed: runId={} kind={}", runId, failed.kind());
                    count("failed");
                }
            }
        } catch (RuntimeException e) {
            // The message is left out: a provider error can quote the request.
            LOG.warn("Run insight failed: runId={} error={}", runId, e.getClass().getSimpleName());
            count("failed");
        }
    }

    private void store(UUID runId, String json) {
        Optional<InsightAnswer> answer = InsightAnswer.parse(json);
        if (answer.isEmpty()) {
            LOG.warn("Run insight unusable: runId={} the answer has no usable headline", runId);
            count("unparseable");
            return;
        }
        if (workflowRunRepository.writeInsightIfAbsent(runId, toJson(answer.get())) == 1) {
            runUpdates.touched(runId);
            count("written");
        } else {
            count("skipped");
        }
    }

    private static String toJson(InsightAnswer answer) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("headline", answer.headline());
        ArrayNode highlights = node.putArray("highlights");
        answer.highlights().forEach(highlights::add);
        return node.toString();
    }

    private void count(String outcome) {
        meters.counter(METER, "outcome", outcome).increment();
    }
}
