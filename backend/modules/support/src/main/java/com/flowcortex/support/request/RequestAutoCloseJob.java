package com.flowcortex.support.request;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.support.request.RequestRepository.RequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Closes a resolution 7 days after the member first opened it; one they never opened stays resolved. Also drops
 * idempotency keys past their 24 hours. Runs on one instance at a time through the cluster runner; each close
 * stays a conditional update.
 */
@Component
public class RequestAutoCloseJob implements ClusterJob {

    static final Duration SEEN_BEFORE_CLOSE = Duration.ofDays(7);

    private static final Logger LOG = LoggerFactory.getLogger(RequestAutoCloseJob.class);

    private final RequestRepository requests;
    private final RequestService service;
    private final IdempotencyGuard idempotency;
    private final Clock clock;
    private final Duration interval;

    RequestAutoCloseJob(RequestRepository requests, RequestService service, IdempotencyGuard idempotency,
            Clock clock, @Value("${flowcortex.support.retention.interval:PT1H}") Duration interval) {
        this.requests = requests;
        this.service = service;
        this.idempotency = idempotency;
        this.clock = clock;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "support.request-auto-close";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void run() {
        Instant now = Instant.now(clock);
        int closed = 0;
        Instant cutoff = now.minus(SEEN_BEFORE_CLOSE);
        for (RequestRow row : requests.seenResolutionsBefore(cutoff)) {
            if (service.autoClose(row, cutoff)) {
                closed++;
            }
        }
        int purged = idempotency.purgeOlderThan(now.minus(IdempotencyGuard.WINDOW));
        LOG.info("support.request.retention closed={} idempotency_purged={}", closed, purged);
    }
}
