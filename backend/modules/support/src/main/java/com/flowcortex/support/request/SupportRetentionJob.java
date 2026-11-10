package com.flowcortex.support.request;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportLifecycleSettings;
import com.flowcortex.support.request.RetentionRepository.ClosedRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes requests that are closed and whose last activity is older than the configured months, with their
 * messages and attachment rows; a request in any other status is never touched. Each batch of
 * {@value #BATCH_SIZE} runs in its own transaction, which also publishes the release of the batch's images,
 * and a run takes at most {@value #MAX_BATCHES} batches. Runs on one instance at a time through the cluster
 * runner; rows another instance holds locked are skipped. Logs counts only.
 */
@Component
public class SupportRetentionJob implements ClusterJob {

    static final int BATCH_SIZE = 200;
    static final int MAX_BATCHES = 10;

    private static final Logger LOG = LoggerFactory.getLogger(SupportRetentionJob.class);

    private final RetentionRepository repository;
    private final Settings settings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration interval;

    SupportRetentionJob(RetentionRepository repository, Settings settings, ApplicationEventPublisher events,
            PlatformTransactionManager transactions, Clock clock,
            @Value("${flowcortex.support.request-retention.interval:PT6H}") Duration interval) {
        this.repository = repository;
        this.settings = settings;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "support.request-retention";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void run() {
        if (!settings.get(SupportLifecycleSettings.RETENTION_ENABLED).effective()) {
            LOG.info("support.request.retention skipped=disabled");
            return;
        }
        int months = settings.get(SupportLifecycleSettings.RETENTION_CLOSED_REQUEST_MONTHS).effective();
        Instant cutoff = clock.instant().atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
        int deleted = 0;
        int released = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Purged purged = transaction.execute(status -> purgeBatch(cutoff));
            deleted += purged.requests();
            released += purged.images();
            if (purged.requests() < BATCH_SIZE) {
                break;
            }
        }
        LOG.info("support.request.retention deleted={} released={}", deleted, released);
    }

    private record Purged(int requests, int images) {
    }

    private Purged purgeBatch(Instant cutoff) {
        List<ClosedRequest> closed = repository.lockClosedBefore(cutoff, BATCH_SIZE);
        if (closed.isEmpty()) {
            return new Purged(0, 0);
        }
        List<UUID> ids = closed.stream().map(ClosedRequest::id).toList();
        Map<UUID, List<StoredAttachment>> images = repository.attachmentsOf(ids);
        repository.deleteRequests(ids);
        int released = 0;
        for (ClosedRequest request : closed) {
            List<StoredAttachment> attachments = images.get(request.id());
            if (attachments != null) {
                events.publishEvent(new SupportAttachmentsReleaseRequested(request.number(), attachments));
                released += attachments.size();
            }
        }
        return new Purged(closed.size(), released);
    }
}
