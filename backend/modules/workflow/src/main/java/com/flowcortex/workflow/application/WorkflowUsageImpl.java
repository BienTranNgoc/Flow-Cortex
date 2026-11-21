package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.AdminWorkflowFilter;
import com.flowcortex.workflow.api.AdminWorkflowRow;
import com.flowcortex.workflow.api.BuilderSummaryView;
import com.flowcortex.workflow.api.DayCount;
import com.flowcortex.workflow.api.WorkflowReportView;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.infrastructure.WorkflowUsageQueries;
import com.flowcortex.workflow.infrastructure.WorkflowUsageQueries.RunAggregate;
import com.flowcortex.workflow.infrastructure.WorkflowUsageQueries.WorkflowRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowUsageImpl implements WorkflowUsage {

    private static final int REPORT_DAYS = 30;
    private static final Duration RECENT = Duration.ofDays(7);
    private static final RunAggregate NO_RUNS = new RunAggregate(0, 0, null);

    private final WorkflowUsageQueries queries;
    private final UserDirectory userDirectory;
    private final Clock clock;

    WorkflowUsageImpl(WorkflowUsageQueries queries, UserDirectory userDirectory, Clock clock) {
        this.queries = queries;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<AdminWorkflowRow> listWorkflows(AdminWorkflowFilter filter, int page, int size) {
        List<WorkflowRow> rows = queries.pageWorkflows(filter, page, size);
        long total = queries.countWorkflows(filter);
        return new PageView<>(toRows(rows), page, size, total);
    }

    @Override
    public PageView<AdminWorkflowRow> exportWorkflows(AdminWorkflowFilter filter) {
        return listWorkflows(filter, 0, EXPORT_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowReportView report(UUID workflowId) {
        WorkflowRow workflow = queries.findWorkflow(workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        AdminWorkflowRow row = toRows(List.of(workflow)).get(0);
        Map<String, Long> stored = queries.statusCounts(workflowId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RunStatus status : RunStatus.values()) {
            String key = status.name().toLowerCase(Locale.ROOT);
            counts.put(key, stored.getOrDefault(key, 0L));
        }
        LocalDate today = today();
        List<DayCount> series = DailySeries.fill(
                queries.dailyCounts(workflowId, DailySeries.windowStart(today, REPORT_DAYS), DailySeries.ZONE),
                today, REPORT_DAYS);
        return new WorkflowReportView(row, counts, row.lastRunAt(), series);
    }

    @Override
    @Transactional(readOnly = true)
    public BuilderSummaryView builderSummary(UUID ownerId) {
        WorkflowUsageQueries.BuilderTotals totals = queries.builderTotals(ownerId);
        return new BuilderSummaryView(totals.workflows(), totals.published(), totals.runs());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayCount> builderChart(UUID ownerId, int days) {
        LocalDate today = today();
        return DailySeries.fill(
                queries.dailyCountsForOwner(ownerId, DailySeries.windowStart(today, days), DailySeries.ZONE),
                today, days);
    }

    private List<AdminWorkflowRow> toRows(List<WorkflowRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Instant since = clock.instant().minus(RECENT);
        Map<UUID, RunAggregate> aggregates = queries.runAggregates(rows.stream().map(WorkflowRow::id).toList(), since);
        Map<UUID, String> emails = userDirectory.findEmailsByIds(rows.stream().map(WorkflowRow::ownerId).distinct().toList());
        return rows.stream().map(row -> {
            RunAggregate runs = aggregates.getOrDefault(row.id(), NO_RUNS);
            return new AdminWorkflowRow(row.id(), row.name(), row.ownerId(), emails.get(row.ownerId()), row.status(),
                    row.shared(), row.steps(), runs.total(), runs.recent(), runs.lastRunAt(), row.createdAt(),
                    row.updatedAt());
        }).toList();
    }

    private LocalDate today() {
        return DailySeries.dayOf(clock.instant());
    }
}
