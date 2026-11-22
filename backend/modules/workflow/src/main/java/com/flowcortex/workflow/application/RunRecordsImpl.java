package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunRecord;
import com.flowcortex.workflow.api.RunRecordStep;
import com.flowcortex.workflow.api.RunRecords;
import com.flowcortex.workflow.infrastructure.RunRecordQueries;
import com.flowcortex.workflow.infrastructure.RunRecordQueries.RunRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads a run for other modules. A Product Video run's {@code input} is a JSON object, so its
 * title comes from the product name and it has no file name; any other run whose first step is
 * remote stored a file name as its input; the rest carry text.
 */
@Service
class RunRecordsImpl implements RunRecords {

    private final RunRecordQueries queries;
    private final StepExecutorRegistry registry;

    RunRecordsImpl(RunRecordQueries queries, StepExecutorRegistry registry) {
        this.queries = queries;
        this.registry = registry;
    }

    @Override
    public Optional<RunRecord> find(UUID runId) {
        return queries.findRun(runId).map(run -> toRecord(run, queries.findSteps(runId)));
    }

    private RunRecord toRecord(RunRow run, List<RunRecordStep> steps) {
        String titleInput = run.input();
        String inputFileName = null;
        if (run.productName() != null) {
            titleInput = run.productName();
        } else if (!steps.isEmpty() && registry.isRemote(steps.get(0).type())) {
            inputFileName = run.input();
        }
        return new RunRecord(run.runId(), run.ownerId(), run.workflowId(), run.workflowName(), run.status(),
                titleInput, inputFileName, run.finishedAt(), steps);
    }
}
