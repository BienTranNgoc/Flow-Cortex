package com.flowcortex.workflow.api;

import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.runhistory.RunExport;
import com.flowcortex.workflow.api.runhistory.RunHistoryFilter;
import com.flowcortex.workflow.api.runhistory.RunHistoryItem;
import com.flowcortex.workflow.api.runhistory.RunHistoryPatch;
import com.flowcortex.workflow.api.runhistory.RunTrayView;
import com.flowcortex.workflow.api.runhistory.UsageSummaryView;
import com.flowcortex.workflow.api.runhistory.WorkflowOption;
import java.util.List;
import java.util.UUID;

/** A member's own run history: always scoped to the caller, never showing a deleted run. */
public interface RunHistoryService {

    /** The caller's runs, newest start first; {@code size} is at most 100. */
    PageView<RunHistoryItem> list(UUID currentUserId, RunHistoryFilter filter, int page, int size);

    /** The distinct workflows of the caller's runs by name, at most 200. */
    List<WorkflowOption> workflows(UUID currentUserId);

    /**
     * Renames and/or stars the run, whatever its status.
     * @throws com.flowcortex.shared.web.NotFoundException if the run is not the caller's or was deleted
     * @throws com.flowcortex.workflow.api.runhistory.RunHistoryRequestException for an invalid title
     */
    RunHistoryItem update(UUID currentUserId, UUID runId, RunHistoryPatch patch);

    /**
     * Soft-deletes a finished run.
     * @throws com.flowcortex.shared.web.NotFoundException if the run is not the caller's or already deleted
     * @throws com.flowcortex.shared.web.ConflictException if the run has not finished
     */
    void delete(UUID currentUserId, UUID runId);

    /** The newest 1000 runs matching the filter, and the number of matches. */
    RunExport export(UUID currentUserId, RunHistoryFilter filter);

    /** The caller's usage over the last {@code days} days (clamped to 1-365), top {@code top} (1-50) workflows. */
    UsageSummaryView usageSummary(UUID currentUserId, int days, int top);

    /** Live runs (at most 50) and runs finished in the last 24 hours (at most 15). */
    RunTrayView tray(UUID currentUserId);
}
