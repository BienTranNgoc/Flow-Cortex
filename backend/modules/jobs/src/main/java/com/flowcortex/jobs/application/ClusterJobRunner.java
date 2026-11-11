package com.flowcortex.jobs.application;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.jobs.infrastructure.ScheduledJobRepository;
import com.flowcortex.jobs.infrastructure.ScheduledJobRepository.Outcome;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the registered {@link ClusterJob}s on this instance whenever the shared schedule says one is due and
 * no other instance holds it. One pass walks the jobs in the registry's order and runs each claimed job
 * inline, so jobs never overlap on this instance. A job that throws is recorded as failed and the pass goes
 * on. The claims this instance holds are released on shutdown, keeping each job's next run.
 */
@Component
@ConditionalOnProperty(name = "flowcortex.jobs.runner.enabled", havingValue = "true", matchIfMissing = true)
public class ClusterJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterJobRunner.class);

    private final ClusterJobRegistry registry;
    private final ScheduledJobRepository schedule;
    private final Clock clock;
    private final String owner = owner();
    private final Map<String, UUID> held = new ConcurrentHashMap<>();
    private final AtomicBoolean registered = new AtomicBoolean();
    private volatile boolean closing;

    @Autowired
    ClusterJobRunner(ClusterJobRegistry registry, ScheduledJobRepository schedule) {
        this(registry, schedule, Clock.systemUTC());
    }

    public ClusterJobRunner(ClusterJobRegistry registry, ScheduledJobRepository schedule, Clock clock) {
        this.registry = registry;
        this.schedule = schedule;
        this.clock = clock;
    }

    /** Writes every job's interval into the schedule; a job seen for the first time is due at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        Instant now = clock.instant();
        for (ClusterJob job : registry.jobs()) {
            schedule.register(job.name(), job.interval(), now);
        }
        registered.set(true);
    }

    /**
     * One walk over the jobs. Spring starts the scheduled poll before the application is ready, so a pass
     * that comes before {@link #register()} has finished does nothing: it would find no row for a new job
     * or read the interval of the previous deploy.
     */
    @Scheduled(fixedDelayString = "${flowcortex.jobs.poll-interval:PT5S}")
    public void runPass() {
        if (!registered.get()) {
            return;
        }
        for (ClusterJob job : registry.jobs()) {
            if (closing) {
                return;
            }
            try {
                runIfDue(job);
            } catch (RuntimeException e) {
                LOG.warn("job outcome=error name={} error={}", job.name(), e.getClass().getSimpleName());
            }
        }
    }

    /** Gives up every claim this instance still holds; a job still running then finishes as {@code lease_lost}. */
    @EventListener(ContextClosedEvent.class)
    public void release() {
        closing = true;
        Instant now = clock.instant();
        for (Map.Entry<String, UUID> claim : held.entrySet()) {
            try {
                schedule.release(claim.getKey(), claim.getValue(), now);
            } catch (RuntimeException e) {
                LOG.warn("job outcome=release_failed name={} error={}", claim.getKey(), e.getClass().getSimpleName());
            }
        }
        held.clear();
    }

    private void runIfDue(ClusterJob job) {
        UUID token = UUID.randomUUID();
        Duration lease = job.lease();
        Instant start = clock.instant();
        if (!schedule.claim(job.name(), token, owner, start, lease)) {
            return;
        }
        held.put(job.name(), token);
        if (closing) {
            // release() may have iterated `held` before the put above, so this claim is given back here.
            held.remove(job.name(), token);
            schedule.release(job.name(), token, clock.instant());
            return;
        }
        Outcome outcome = Outcome.SUCCEEDED;
        String error = null;
        try {
            job.run();
        } catch (RuntimeException e) {
            outcome = Outcome.FAILED;
            error = e.getClass().getSimpleName();
        }
        Instant end = clock.instant();
        boolean finished = schedule.finish(job.name(), token, end, outcome, error);
        held.remove(job.name(), token);
        long millis = Duration.between(start, end).toMillis();
        if (!finished) {
            LOG.warn("job outcome=lease_lost name={} duration_ms={}", job.name(), millis);
        } else if (outcome == Outcome.FAILED) {
            LOG.warn("job outcome=failed name={} duration_ms={} error={}", job.name(), millis, error);
        } else {
            LOG.info("job outcome=succeeded name={} duration_ms={}", job.name(), millis);
        }
    }

    private static String owner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        return host + "/" + HexFormat.of().formatHex(bytes);
    }
}
