package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.application.ModerationRules.Action;
import com.flowcortex.workflow.application.ModerationRules.Normalized;
import com.flowcortex.workflow.application.ModerationRules.Source;
import com.flowcortex.workflow.application.ModerationViews.HistoryEntry;
import com.flowcortex.workflow.application.ModerationViews.ModerationOutcome;
import com.flowcortex.workflow.application.ModerationViews.Person;
import com.flowcortex.workflow.application.ModerationViews.ReportDetailView;
import com.flowcortex.workflow.application.ModerationViews.ReportView;
import com.flowcortex.workflow.application.ModerationViews.WorkflowRef;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowModerationAction;
import com.flowcortex.workflow.domain.WorkflowModerationStatus;
import com.flowcortex.workflow.domain.WorkflowReport;
import com.flowcortex.workflow.infrastructure.WorkflowModerationActionRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReportQueries;
import com.flowcortex.workflow.infrastructure.WorkflowReportQueries.ReportRow;
import com.flowcortex.workflow.infrastructure.WorkflowReportRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff moderation of workflows: the report queue, the actions taken from a report or directly on a workflow, and
 * a workflow's history. An action locks the workflow row, then the report row when there is one, and writes the
 * status, the report and the history entry in one transaction, so concurrent actions apply one after the other.
 */
@Service
public class WorkflowModerationService {

    private final WorkflowRepository workflows;
    private final WorkflowReportRepository reports;
    private final WorkflowModerationActionRepository history;
    private final WorkflowReportQueries queries;
    private final UserDirectory userDirectory;
    private final Clock clock;
    private final TransactionTemplate transaction;

    WorkflowModerationService(WorkflowRepository workflows, WorkflowReportRepository reports,
            WorkflowModerationActionRepository history, WorkflowReportQueries queries, UserDirectory userDirectory,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.workflows = workflows;
        this.reports = reports;
        this.history = history;
        this.queries = queries;
        this.userDirectory = userDirectory;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Hide, remove or restore a workflow without a report. */
    public ModerationOutcome actDirect(UUID staffId, UUID workflowId, Action action, String reason, String note) {
        return act(staffId, workflowId, null, action, reason, note);
    }

    /** Any of the five actions on a report, whatever its prior status; the workflow is the one the report names. */
    public ModerationOutcome actOnReport(UUID staffId, UUID reportId, Action action, String reason, String note) {
        return act(staffId, null, reportId, action, reason, note);
    }

    /** One page of reports newest first; {@code status} and {@code q} (a literal part of the workflow name) are optional. */
    public PageView<ReportView> list(String status, String q, int page, int size) {
        String name = q == null || q.isBlank() ? null : q.strip();
        List<ReportRow> rows = queries.page(status, name, size, (long) page * size);
        return new PageView<>(views(rows), page, size, queries.count(status, name));
    }

    /** @throws NotFoundException {@code workflow.report.notFound} for an unknown report */
    public ReportDetailView reportDetail(UUID reportId) {
        ReportRow row = queries.find(reportId).orElseThrow(() -> reportNotFound(reportId));
        return new ReportDetailView(views(List.of(row)).get(0), row.internalNote(), entries(row.workflowId()));
    }

    /** Newest first, a deleted workflow's included. @throws NotFoundException {@code workflow.notFound} for an unknown id */
    public List<HistoryEntry> history(UUID workflowId) {
        if (!workflows.existsById(workflowId)) {
            throw workflowNotFound(workflowId);
        }
        return entries(workflowId);
    }

    private ModerationOutcome act(UUID staffId, UUID workflowId, UUID reportId, Action action, String reason,
            String note) {
        Source source = reportId == null ? Source.DIRECT : Source.REPORT;
        // Text refusals answer before any lookup or lock; the deleted-workflow refusal needs the row, so it is checked again below.
        ModerationRules.checkAction(workflowId, action, source, false, reason, note);
        return transaction.execute(status -> {
            UUID targetId = reportId == null ? workflowId : queries.find(reportId)
                    .orElseThrow(() -> reportNotFound(reportId)).workflowId();
            Workflow workflow = workflows.findByIdForModeration(targetId)
                    .orElseThrow(() -> workflowNotFound(targetId));
            Normalized text = ModerationRules.checkAction(targetId, action, source, workflow.getDeletedAt() != null,
                    reason, note);
            if (action.moderationStatusAfter() != null && ProductVideoWorkflow.ID.equals(workflow.getId())) {
                throw new ConflictException(new LocalizedMessage("workflow.system.readOnly"));
            }
            WorkflowReport report = reportId == null ? null : reports.findByIdForUpdate(reportId)
                    .orElseThrow(() -> reportNotFound(reportId));
            // Read only now, with both locks held, so history order is commit order.
            Instant now = clock.instant();
            WorkflowModerationStatus after = action.moderationStatusAfter() == null ? workflow.getModerationStatus()
                    : WorkflowModerationStatus.valueOf(action.moderationStatusAfter().toUpperCase(Locale.ROOT));
            if (after != workflow.getModerationStatus()) {
                workflow.moderate(after);
            }
            if (report != null) {
                report.review(action.reportStatusAfter(), action.code(), staffId, now, text.internalNote());
            }
            history.save(new WorkflowModerationAction(UUID.randomUUID(), targetId, reportId, source.code(),
                    action.code(), staffId, text.reason(), text.internalNote(), after, now));
            return new ModerationOutcome(targetId, after.name().toLowerCase(Locale.ROOT));
        });
    }

    private List<HistoryEntry> entries(UUID workflowId) {
        List<WorkflowModerationAction> actions = history.findByWorkflowIdOrderByCreatedAtDescIdDesc(workflowId);
        Map<UUID, String> names = actions.isEmpty() ? Map.of()
                : userDirectory.findNamesByIds(actions.stream().map(WorkflowModerationAction::getModeratorId)
                        .collect(Collectors.toSet()));
        return actions.stream().map(action -> new HistoryEntry(action.getId(), action.getAction(), action.getSource(),
                action.getReportId(), action.getModeratorId(), names.get(action.getModeratorId()), action.getReason(),
                action.getInternalNote(), action.getModerationStatus().name().toLowerCase(Locale.ROOT),
                action.getCreatedAt())).toList();
    }

    /** One name lookup and one email lookup for the whole page, whatever its size. */
    private List<ReportView> views(List<ReportRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> contactIds = new LinkedHashSet<>();
        Set<UUID> nameIds = new LinkedHashSet<>();
        for (ReportRow row : rows) {
            contactIds.add(row.reporterId());
            contactIds.add(row.ownerId());
            if (row.reviewerId() != null) {
                nameIds.add(row.reviewerId());
            }
        }
        nameIds.addAll(contactIds);
        Map<UUID, String> names = userDirectory.findNamesByIds(nameIds);
        Map<UUID, String> emails = userDirectory.findEmailsByIds(contactIds);
        List<ReportView> views = new ArrayList<>(rows.size());
        for (ReportRow row : rows) {
            Person reviewer = row.reviewerId() == null ? null
                    : new Person(row.reviewerId(), names.get(row.reviewerId()), null);
            WorkflowRef workflow = new WorkflowRef(row.workflowId(), row.workflowName(), row.workflowStatus(),
                    row.workflowVisibility(), row.moderationStatus(), row.workflowDeleted(),
                    person(row.ownerId(), names, emails));
            views.add(new ReportView(row.id(), row.reason(), row.details(), row.status(), row.resolution(),
                    row.reviewedAt(), row.createdAt(), person(row.reporterId(), names, emails), reviewer, workflow));
        }
        return views;
    }

    private static Person person(UUID id, Map<UUID, String> names, Map<UUID, String> emails) {
        return new Person(id, names.get(id), emails.get(id));
    }

    private static NotFoundException workflowNotFound(UUID workflowId) {
        return new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId));
    }

    private static NotFoundException reportNotFound(UUID reportId) {
        return new NotFoundException(new LocalizedMessage("workflow.report.notFound", reportId));
    }
}
