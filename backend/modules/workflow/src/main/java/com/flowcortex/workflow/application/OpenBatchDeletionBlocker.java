package com.flowcortex.workflow.application;

import com.flowcortex.user.api.AccountDeletionBlocker;
import com.flowcortex.workflow.infrastructure.BatchRunRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** An account with a batch still starting runs cannot be deleted: the batch would keep spending after the request. */
@Component
class OpenBatchDeletionBlocker implements AccountDeletionBlocker {

    private static final Set<String> OPEN = Set.of(BatchStatus.RUNNING.value(), BatchStatus.PAUSED.value());

    private final BatchRunRepository batches;

    OpenBatchDeletionBlocker(BatchRunRepository batches) {
        this.batches = batches;
    }

    @Override
    public String key() {
        return "workflow.open_batch";
    }

    @Override
    public int defaultSortOrder() {
        return 2;
    }

    @Override
    public String defaultMessageVi() {
        return "Bạn có lô chạy workflow chưa hoàn tất. Hãy hủy lô hoặc chờ lô chạy xong, rồi thử lại.";
    }

    @Override
    public String defaultMessageEn() {
        return "A batch of workflow runs is still open. Cancel it or wait until it finishes, then try again.";
    }

    @Override
    public boolean blocks(UUID userId) {
        return batches.existsByOwnerIdAndStatusIn(userId, OPEN);
    }
}
