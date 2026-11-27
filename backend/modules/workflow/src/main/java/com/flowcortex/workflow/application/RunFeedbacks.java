package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunFeedbackView;
import com.flowcortex.workflow.application.RunFeedbackRules.Feedback;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's verdict on a run. One verdict is kept per run and a later one replaces it whole, so a thumbs-up after
 * a thumbs-down leaves no issue or detail behind. The detail is the member's own words: it is stored but never logged.
 */
@Service
public class RunFeedbacks {

    private static final Logger LOG = LoggerFactory.getLogger(RunFeedbacks.class);

    private final WorkflowRunRepository runRepository;
    private final RunUpdates runUpdates;
    private final Clock clock;

    RunFeedbacks(WorkflowRunRepository runRepository, RunUpdates runUpdates, Clock clock) {
        this.runRepository = runRepository;
        this.runUpdates = runUpdates;
        this.clock = clock;
    }

    /**
     * Stores the verdict on the caller's run, whatever its status.
     *
     * @throws NotFoundException for a run that is not the caller's, is deleted or does not exist
     * @throws RunFeedbackRequestException naming {@code verdict}, {@code issue} or {@code detail} when it is refused
     */
    @Transactional
    public RunFeedbackView record(UUID caller, UUID runId, String verdict, String issue, String detail) {
        WorkflowRun run = runRepository.findByIdAndOwnerId(runId, caller)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        Feedback feedback;
        try {
            feedback = RunFeedbackRules.validate(verdict, issue, detail);
        } catch (FieldRefusal e) {
            throw invalid(e);
        }
        run.giveFeedback(feedback.verdict(), feedback.issue(), feedback.detail(), clock.instant());
        runUpdates.touched(runId);
        LOG.info("Run feedback recorded: run {} verdict={} issue={} hasDetail={}", runId, feedback.verdict(),
                feedback.issue(), feedback.detail() != null);
        return new RunFeedbackView(run.getFeedbackVerdict(), run.getFeedbackIssue(), run.getFeedbackDetail(),
                run.getFeedbackAt());
    }

    private static RunFeedbackRequestException invalid(FieldRefusal refusal) {
        LocalizedMessage detail = switch (refusal.field()) {
            case "issue" -> new LocalizedMessage("workflow.run.feedback.issueInvalid");
            case "detail" -> refusal.reason() == FieldRefusal.Reason.TOO_LONG
                    ? new LocalizedMessage("workflow.run.feedback.detailTooLong")
                    : new LocalizedMessage("workflow.run.feedback.detailInvalid");
            default -> new LocalizedMessage("workflow.run.feedback.verdictInvalid");
        };
        return new RunFeedbackRequestException(refusal.field(), detail);
    }
}
