package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.WorkflowDataEntry;
import com.flowcortex.workflow.infrastructure.WorkflowDataEntryRepository;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * {@code load_template} — reads the {@code template} entry its input names from the data store of the
 * run's own workflow and returns the merged working set as one JSON object. Priced free.
 */
@Component
class LoadTemplateStepExecutor implements StepExecutor {

    static final String TYPE = "load_template";

    private final WorkflowDataEntryRepository entries;

    LoadTemplateStepExecutor(WorkflowDataEntryRepository entries) {
        this.entries = entries;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    @Override
    public Duration maxRuntime() {
        return FailStepExecutor.INLINE_MAX_RUNTIME;
    }

    @Override
    public String execute(StepContext context) {
        TemplateLoad.Result result = TemplateLoad.load(context.input(),
                key -> entries.findTemplateForRun(context.runId(), key).map(WorkflowDataEntry::getValue));
        return switch (result) {
            case TemplateLoad.Loaded loaded -> loaded.json();
            case TemplateLoad.Failed failed -> throw new StepFailedException(failed.code(), failed.message());
        };
    }
}
