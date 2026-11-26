package com.flowcortex.workflow.application;

import com.flowcortex.user.api.AccountDeletionBlocker;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** An account with a run that is still moving cannot be deleted: its steps would keep spending after the request. */
@Component
class ActiveRunDeletionBlocker implements AccountDeletionBlocker {

    private static final Set<RunStatus> ACTIVE = Set.of(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL,
            RunStatus.CANCELLING);

    private final WorkflowRunRepository runs;

    ActiveRunDeletionBlocker(WorkflowRunRepository runs) {
        this.runs = runs;
    }

    @Override
    public String key() {
        return "workflow.active_run";
    }

    @Override
    public int defaultSortOrder() {
        return 0;
    }

    @Override
    public String defaultMessageVi() {
        return "Bạn có workflow đang chạy. Hãy chờ chạy xong hoặc liên hệ bộ phận hỗ trợ, rồi thử lại.";
    }

    @Override
    public String defaultMessageEn() {
        return "A workflow run is still in progress. Wait until it finishes, or contact support, then try again.";
    }

    @Override
    public boolean blocks(UUID userId) {
        return runs.existsByOwnerIdAndStatusIn(userId, ACTIVE);
    }
}
