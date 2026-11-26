package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.application.ModerationRules.NormalizedReport;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.WorkflowReportRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's report of a workflow they can see. Reports of one member are serialised by a transaction-scoped advisory
 * lock, so the hourly bound reads every earlier report of that member and two concurrent requests at the bound store
 * one.
 */
@Service
public class WorkflowReportService {

    static final Duration BOUND_WINDOW = Duration.ofHours(1);

    private static final String REPORTER_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    private static final String REPORTER_LOCK_PREFIX = "workflow_report:";

    private final WorkflowRepository workflows;
    private final WorkflowReportRepository reports;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    WorkflowReportService(WorkflowRepository workflows, WorkflowReportRepository reports, JdbcTemplate jdbc,
            Clock clock) {
        this.workflows = workflows;
        this.reports = reports;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Stores the report unless the caller already reported this workflow, in which case nothing is written and the
     * answer is the same. Refusals, first to answer: 422 for the text, 404 when the caller cannot see the workflow, 403
     * for the caller's own workflow, 429 when the caller already filed the hourly bound of reports on other workflows.
     */
    @Transactional
    public void report(UUID callerId, UUID workflowId, String reason, String details) {
        NormalizedReport normalized = ModerationRules.checkReport(reason, details);
        Workflow workflow = workflows.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        if (workflow.getOwnerId().equals(callerId)) {
            throw new LocalizedStatusException(HttpStatus.FORBIDDEN, new LocalizedMessage("workflow.report.own"));
        }

        jdbc.query(REPORTER_LOCK, (RowCallbackHandler) row -> {
        }, REPORTER_LOCK_PREFIX + callerId);
        if (reports.existsByWorkflowIdAndReporterId(workflowId, callerId)) {
            return;
        }
        Instant now = clock.instant();
        if (ModerationRules.overBound(reports.countByReporterIdAndCreatedAtAfter(callerId, now.minus(BOUND_WINDOW)))) {
            throw new LocalizedStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    new LocalizedMessage("workflow.report.tooMany"));
        }
        reports.insertIfAbsent(UUID.randomUUID(), workflowId, callerId, normalized.reason(), normalized.details(), now);
    }
}
