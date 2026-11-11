package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunHistory;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunHistoryImpl implements RunHistory {

    private final WorkflowRunRepository runs;

    RunHistoryImpl(WorkflowRunRepository runs) {
        this.runs = runs;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCompletedRun(UUID ownerId) {
        return runs.existsByOwnerIdAndStatus(ownerId, RunStatus.COMPLETED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompletedRunRef> earliestCompletedRun(UUID ownerId) {
        return runs.findFirstByOwnerIdAndStatusOrderByStartedAtAscIdAsc(ownerId, RunStatus.COMPLETED)
                .map(run -> new CompletedRunRef(run.getId(), run.getWorkflowId(), run.getFinishedAt()));
    }
}
