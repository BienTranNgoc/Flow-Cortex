package com.flowcortex.media.infrastructure;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Declares the media topology when the application first becomes ready, and only then starts
 * the media listeners; until then readiness is refusing. A queue that already exists with other
 * arguments — which a broker never changes in place — is final: one ERROR, and no listener ever
 * starts. A broker that cannot be reached is not: a WARN, and the declaration is retried with
 * backoff until it succeeds, which starts the listeners and makes the backend ready.
 *
 * <p>Depends on Boot's availability bean so that bean is registered as a listener first: the
 * refusal is published while Boot's accepting-traffic event is still being delivered, and must
 * reach that bean after it.
 */
@Component
@DependsOn("applicationAvailability")
class MediaTopologyCheck implements ApplicationListener<AvailabilityChangeEvent<ReadinessState>> {

    private static final Logger LOG = LoggerFactory.getLogger(MediaTopologyCheck.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    private static final Duration LONGEST_RETRY = Duration.ofSeconds(30);
    /** Read by the connection factory's close logger, which has no reference to this bean. */
    private static final AtomicBoolean DECLARING = new AtomicBoolean();

    private final RabbitAdmin admin;
    private final RabbitListenerEndpointRegistry listeners;
    private final ApplicationEventPublisher events;
    private final TaskScheduler scheduler;
    private final AtomicBoolean started = new AtomicBoolean();

    MediaTopologyCheck(RabbitAdmin admin, RabbitListenerEndpointRegistry listeners, ApplicationEventPublisher events,
            TaskScheduler scheduler) {
        this.admin = admin;
        this.listeners = listeners;
        this.events = events;
        this.scheduler = scheduler;
    }

    /**
     * Whether a queue-argument mismatch on the broker belongs to this check. After a mismatch it
     * stays set: the backend never declares or consumes again until it is restarted.
     */
    static boolean isDeclaring() {
        return DECLARING.get();
    }

    @Override
    public void onApplicationEvent(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() != ReadinessState.ACCEPTING_TRAFFIC || !started.compareAndSet(false, true)) {
            return;
        }
        if (!declare(FIRST_RETRY)) {
            AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    /** {@code true} once the topology is declared and the listeners run; otherwise a retry may be scheduled. */
    private boolean declare(Duration retryIn) {
        DECLARING.set(true);
        try {
            admin.initialize();
        } catch (RuntimeException e) {
            String mismatch = mismatch(e);
            if (mismatch != null) {
                LOG.error("The media broker topology could not be declared: {}. Queue arguments cannot change on an"
                        + " existing queue: delete and re-create the queue (locally `docker compose down -v`). The backend"
                        + " stays not ready and consumes nothing.", mismatch);
                return false;
            }
            DECLARING.set(false);
            LOG.warn("The media broker could not be reached ({}); the backend stays not ready and declares the topology"
                    + " again in {} s", e.getMessage(), retryIn.toSeconds());
            scheduler.schedule(() -> retry(retryIn), Instant.now().plus(retryIn));
            return false;
        }
        DECLARING.set(false);
        listeners.getListenerContainers().forEach(Lifecycle::start);
        LOG.info("Media broker topology declared; {} listeners started", listeners.getListenerContainers().size());
        return true;
    }

    private void retry(Duration lastDelay) {
        Duration next = lastDelay.multipliedBy(2);
        if (declare(next.compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : next)) {
            AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    /** The broker's own words when it refused a declaration as inequivalent (they name the queue and argument), else null. */
    private static String mismatch(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException signal && signal.getReason() instanceof AMQP.Channel.Close close
                    && close.getReplyCode() == AMQP.PRECONDITION_FAILED) {
                return close.getReplyText();
            }
        }
        return null;
    }
}
