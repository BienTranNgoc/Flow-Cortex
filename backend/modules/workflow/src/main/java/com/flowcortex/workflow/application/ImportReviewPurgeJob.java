package com.flowcortex.workflow.application;

import com.flowcortex.workflow.infrastructure.WorkflowImportReviewRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes import reviews that expired without being imported. An imported review is the ledger of its token and
 * stays; the delete is idempotent, so several instances may run it at once.
 */
@Component
class ImportReviewPurgeJob {

    private static final Logger LOG = LoggerFactory.getLogger(ImportReviewPurgeJob.class);

    private final WorkflowImportReviewRepository reviewRepository;
    private final Clock clock;

    ImportReviewPurgeJob(WorkflowImportReviewRepository reviewRepository, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowcortex.workflow.import-review.purge-interval:PT15M}")
    @Transactional
    public void purge() {
        int deleted = reviewRepository.deleteExpiredUnimported(clock.instant());
        LOG.debug("Import review purge: deleted {} expired reviews", deleted);
    }
}
