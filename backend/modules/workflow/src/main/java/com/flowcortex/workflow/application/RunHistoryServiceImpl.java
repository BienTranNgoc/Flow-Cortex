package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.RunHistoryService;
import com.flowcortex.workflow.api.runhistory.RunExport;
import com.flowcortex.workflow.api.runhistory.RunHistoryFilter;
import com.flowcortex.workflow.api.runhistory.RunHistoryItem;
import com.flowcortex.workflow.api.runhistory.RunHistoryPatch;
import com.flowcortex.workflow.api.runhistory.RunTrayView;
import com.flowcortex.workflow.api.runhistory.UsageSummaryView;
import com.flowcortex.workflow.api.runhistory.WorkflowOption;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunHistoryRow;
import com.flowcortex.workflow.infrastructure.RunUsageRow;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link RunHistoryService}; every read and write is owner-scoped and skips deleted runs. */
@Service
class RunHistoryServiceImpl implements RunHistoryService {

    static final int EXPORT_CAP = 1000;
    static final int WORKFLOW_OPTIONS_CAP = 200;
    static final int TRAY_ACTIVE_CAP = 50;
    static final int TRAY_RECENT_CAP = 15;
    static final Duration TRAY_RECENT_WINDOW = Duration.ofHours(24);

    private final WorkflowRunRepository runs;
    private final WorkflowRepository workflows;
    private final CreditAccounts credits;
    private final Clock clock;

    RunHistoryServiceImpl(WorkflowRunRepository runs, WorkflowRepository workflows, CreditAccounts credits,
            Clock clock) {
        this.runs = runs;
        this.workflows = workflows;
        this.credits = credits;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<RunHistoryItem> list(UUID currentUserId, RunHistoryFilter filter, int page, int size) {
        Page<RunHistoryRow> rows = history(currentUserId, filter, PageRequest.of(page, size));
        return new PageView<>(items(rows.getContent()), page, size, rows.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowOption> workflows(UUID currentUserId) {
        return runs.findHistoryWorkflows(currentUserId, PageRequest.of(0, WORKFLOW_OPTIONS_CAP)).stream()
                .map(choice -> new WorkflowOption(choice.id(), choice.name())).toList();
    }

    @Override
    @Transactional
    public RunHistoryItem update(UUID currentUserId, UUID runId, RunHistoryPatch patch) {
        WorkflowRun run = runs.findByIdAndOwnerId(runId, currentUserId).orElseThrow(() -> notFound(runId));
        if (patch.titleSent()) {
            run.rename(RunTitles.normalize(patch.title()).orElse(null));
        }
        if (patch.favorite() != null) {
            run.setFavorite(patch.favorite());
        }
        String workflowName = workflows.findById(run.getWorkflowId()).map(Workflow::getName).orElse("");
        Long charged = credits.enabled() ? credits.chargedForRun(runId) : null;
        return item(run, workflowName, charged);
    }

    /**
     * Takes the run lock every status transition takes before the conditional write, so the status the write
     * sees cannot change under it: a run that finishes first is deleted, one still live is refused.
     */
    @Override
    @Transactional
    public void delete(UUID currentUserId, UUID runId) {
        runs.lockById(runId);
        if (runs.softDelete(runId, currentUserId, clock.instant()) == 1) {
            return;
        }
        runs.findByIdAndOwnerId(runId, currentUserId).orElseThrow(() -> notFound(runId));
        throw new ConflictException(new LocalizedMessage("workflow.run.stillActive"));
    }

    @Override
    @Transactional(readOnly = true)
    public RunExport export(UUID currentUserId, RunHistoryFilter filter) {
        Page<RunHistoryRow> rows = history(currentUserId, filter, PageRequest.of(0, EXPORT_CAP));
        return new RunExport(items(rows.getContent()), rows.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public UsageSummaryView usageSummary(UUID currentUserId, int days, int top) {
        int window = UsageSummaries.clampDays(days);
        LocalDate today = DailySeries.dayOf(clock.instant());
        Instant since = DailySeries.windowStart(today, window);
        List<RunUsageRow> rows = runs.findUsageRows(currentUserId, since);
        boolean creditsOn = credits.enabled();
        Map<UUID, Long> charges = creditsOn ? credits.chargedForRunsOf(currentUserId, since) : null;
        Long balance = creditsOn ? credits.balance(currentUserId) : null;
        return UsageSummaries.summarize(rows, charges, balance, today, window, UsageSummaries.clampTop(top));
    }

    @Override
    @Transactional(readOnly = true)
    public RunTrayView tray(UUID currentUserId) {
        List<RunHistoryRow> active = runs.findTrayActive(currentUserId, PageRequest.of(0, TRAY_ACTIVE_CAP));
        List<RunHistoryRow> recent = runs.findTrayRecent(currentUserId, clock.instant().minus(TRAY_RECENT_WINDOW),
                PageRequest.of(0, TRAY_RECENT_CAP));
        List<RunHistoryItem> all = items(Stream.concat(active.stream(), recent.stream()).toList());
        return new RunTrayView(all.subList(0, active.size()), all.subList(active.size(), all.size()));
    }

    private Page<RunHistoryRow> history(UUID ownerId, RunHistoryFilter filter, PageRequest page) {
        RunStatus status = filter.status() == null ? null : RunStatus.valueOf(filter.status().toUpperCase(Locale.ROOT));
        return runs.findHistory(ownerId, status, filter.workflowId(), filter.from(), filter.to(),
                filter.favoriteOnly(), page);
    }

    /** One grouped credits read for all the rows, never one per row. */
    private List<RunHistoryItem> items(List<RunHistoryRow> rows) {
        Map<UUID, Long> charges = credits.enabled()
                ? credits.chargedForRuns(rows.stream().map(row -> row.run().getId()).toList()) : null;
        return rows.stream().map(row -> item(row.run(), row.workflowName(),
                charges == null ? null : charges.getOrDefault(row.run().getId(), 0L))).toList();
    }

    private static RunHistoryItem item(WorkflowRun run, String workflowName, Long creditsCharged) {
        Long duration = run.getFinishedAt() == null ? null
                : Duration.between(run.getStartedAt(), run.getFinishedAt()).toSeconds();
        return new RunHistoryItem(run.getId(), run.getWorkflowId(), workflowName, run.getTitle(),
                run.getStatus().name().toLowerCase(Locale.ROOT), run.isFavorite(), run.getStartedAt(),
                run.getFinishedAt(), duration, creditsCharged, run.getErrorMessage());
    }

    private static NotFoundException notFound(UUID runId) {
        return new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId));
    }
}
