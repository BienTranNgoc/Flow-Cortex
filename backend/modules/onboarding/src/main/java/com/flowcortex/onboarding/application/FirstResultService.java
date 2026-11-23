package com.flowcortex.onboarding.application;

import com.flowcortex.onboarding.domain.FirstResultState;
import com.flowcortex.onboarding.infrastructure.FirstResultRepository;
import com.flowcortex.onboarding.infrastructure.FirstResultRepository.Reported;
import com.flowcortex.workflow.api.RunHistory;
import com.flowcortex.workflow.api.RunHistory.CompletedRunRef;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The once-per-account report of a member's first completed workflow run. */
@Service
public class FirstResultService {

    private static final Logger LOG = LoggerFactory.getLogger(FirstResultService.class);

    private final FirstResultRepository reports;
    private final RunHistory runHistory;
    private final Clock clock;

    FirstResultService(FirstResultRepository reports, RunHistory runHistory, Clock clock) {
        this.reports = reports;
        this.runHistory = runHistory;
        this.clock = clock;
    }

    /**
     * Reports the member's earliest completed run. Empty when no run has completed or the account is
     * already reported; the primary key on the report decides concurrent claims, not this read.
     */
    @Transactional
    public Optional<Reported> claim(UUID userId) {
        Optional<CompletedRunRef> earliest = runHistory.earliestCompletedRun(userId);
        if (earliest.isEmpty()) {
            return Optional.empty();
        }
        CompletedRunRef run = earliest.get();
        Instant now = clock.instant();
        Instant resultAt = run.finishedAt() != null ? run.finishedAt() : now;
        Optional<Reported> claimed = reports.insertClaim(userId, run.runId(), run.workflowId(), resultAt, now);
        claimed.ifPresent(r -> LOG.info("onboarding.first_result outcome=claimed userId={} runId={} workflowId={}",
                userId, r.runId(), r.workflowId()));
        return claimed;
    }

    @Transactional(readOnly = true)
    public FirstResultState state(UUID userId, boolean firstRunDone) {
        return FirstResultState.of(reports.exists(userId), firstRunDone);
    }
}
