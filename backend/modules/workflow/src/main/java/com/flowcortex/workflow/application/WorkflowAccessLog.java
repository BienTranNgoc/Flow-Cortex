package com.flowcortex.workflow.application;

import com.flowcortex.workflow.infrastructure.WorkflowAccessLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends the access-log entry of a key read, at most one per workflow, accessor and 10-minute clock window.
 * The write has its own transaction and never fails the read: a failure is logged with the workflow id only,
 * never the key.
 */
@Component
class WorkflowAccessLog {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowAccessLog.class);

    /** Length of the dedupe window, in seconds. */
    static final long WINDOW_SECONDS = 600;

    private final WorkflowAccessLogRepository repository;
    private final AccessorKeys accessorKeys;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    WorkflowAccessLog(WorkflowAccessLogRepository repository, AccessorKeys accessorKeys,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.accessorKeys = accessorKeys;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** {@code userId} is {@code null} for an anonymous reader, who is told apart by a keyed hash of the client. */
    void record(UUID workflowId, UUID userId, ShareOrigin origin) {
        try {
            Instant now = clock.instant();
            Instant window = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), WINDOW_SECONDS)
                    * WINDOW_SECONDS);
            String accessor = userId != null ? "u:" + userId
                    : accessorKeys.anonymous(origin.remoteAddr(), origin.userAgent());
            ownTransaction.executeWithoutResult(status -> repository.insertOnce(UUID.randomUUID(), workflowId,
                    userId, origin.network(), origin.userAgent(), accessor, window, now));
        } catch (RuntimeException e) {
            LOG.error("Could not write the access log entry of workflow {}", workflowId, e);
        }
    }
}
