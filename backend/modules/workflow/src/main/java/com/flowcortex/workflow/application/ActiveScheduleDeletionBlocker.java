package com.flowcortex.workflow.application;

import com.flowcortex.user.api.AccountDeletionBlocker;
import com.flowcortex.workflow.domain.WorkflowSchedule;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An account with an active schedule cannot be deleted: the schedule would keep starting runs for it. Reads the
 * rows whatever the schedules switch says, since the switch can come back on while a request is pending.
 */
@Component
class ActiveScheduleDeletionBlocker implements AccountDeletionBlocker {

    private final WorkflowScheduleRepository schedules;

    ActiveScheduleDeletionBlocker(WorkflowScheduleRepository schedules) {
        this.schedules = schedules;
    }

    @Override
    public String key() {
        return "workflow.active_schedule";
    }

    @Override
    public int defaultSortOrder() {
        return 3;
    }

    @Override
    public String defaultMessageVi() {
        return "Bạn có lịch chạy workflow đang bật. Hãy tạm dừng hoặc xóa lịch, rồi thử lại.";
    }

    @Override
    public String defaultMessageEn() {
        return "A workflow schedule is still active. Pause or delete it, then try again.";
    }

    @Override
    public boolean blocks(UUID userId) {
        return schedules.existsByOwnerIdAndStatus(userId, WorkflowSchedule.ACTIVE);
    }
}
