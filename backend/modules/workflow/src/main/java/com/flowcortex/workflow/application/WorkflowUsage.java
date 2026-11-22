package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.AdminWorkflowFilter;
import com.flowcortex.workflow.api.AdminWorkflowRow;
import com.flowcortex.workflow.api.BuilderSummaryView;
import com.flowcortex.workflow.api.DayCount;
import com.flowcortex.workflow.api.WorkflowReportView;
import java.util.List;
import java.util.UUID;

/**
 * Read-only usage figures over workflows and their runs. Lives outside {@code api} on purpose: only this
 * module's controllers read across owners.
 */
public interface WorkflowUsage {

    /** The most rows one export carries. */
    int EXPORT_LIMIT = 1000;

    /** Live workflows of every owner, most recently updated first. */
    PageView<AdminWorkflowRow> listWorkflows(AdminWorkflowFilter filter, int page, int size);

    /** The first {@link #EXPORT_LIMIT} workflows of the filter; {@code total} counts every match. */
    PageView<AdminWorkflowRow> exportWorkflows(AdminWorkflowFilter filter);

    /** 404 {@code workflow.notFound} for an unknown or deleted workflow. */
    WorkflowReportView report(UUID workflowId);

    BuilderSummaryView builderSummary(UUID ownerId);

    /** Runs per local day over the owner's live workflows: exactly {@code days} points, oldest first. */
    List<DayCount> builderChart(UUID ownerId, int days);
}
