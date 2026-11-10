package com.flowcortex.support.request;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportLifecycleSettings;
import com.flowcortex.support.request.RequestRepository.RequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Closes a resolution the configured number of days after the member first opened it; one they never opened
 * stays resolved, and nothing closes while auto-close is switched off. Also drops idempotency keys past their
 * 24 hours, switch or no switch. Runs on one instance at a time through the cluster runner; each close stays a
 * conditional update, and a run closes at most {@value #BATCH} (the rest close on the next run).
 */
@Component
public class RequestAutoCloseJob implements ClusterJob {

    static final int BATCH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(RequestAutoCloseJob.class);

    private final RequestRepository requests;
    private final RequestService service;
    private final IdempotencyGuard idempotency;
    private final Settings settings;
    private final Clock clock;
    private final Duration interval;

    RequestAutoCloseJob(RequestRepository requests, RequestService service, IdempotencyGuard idempotency,
            Settings settings, Clock clock,
            @Value("${flowcortex.support.retention.interval:PT1H}") Duration interval) {
        this.requests = requests;
        this.service = service;
        this.idempotency = idempotency;
        this.settings = settings;
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
        boolean enabled = settings.get(SupportLifecycleSettings.AUTO_CLOSE_ENABLED).effective();
        int closed = 0;
        if (enabled) {
            int days = settings.get(SupportLifecycleSettings.AUTO_CLOSE_RESOLVED_DAYS).effective();
            Instant cutoff = now.minus(Duration.ofDays(days));
            for (RequestRow row : requests.seenResolutionsBefore(cutoff, BATCH)) {
                if (service.autoClose(row, cutoff)) {
                    closed++;
                }
            }
        }
        int purged = idempotency.purgeOlderThan(now.minus(IdempotencyGuard.WINDOW));
        LOG.info("support.request.retention closed={}{} idempotency_purged={}", closed,
                enabled ? "" : " skipped=disabled", purged);
    }
}
