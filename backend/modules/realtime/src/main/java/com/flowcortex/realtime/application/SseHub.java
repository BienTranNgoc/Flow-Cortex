package com.flowcortex.realtime.application;

import com.flowcortex.realtime.api.TopicAuthorizer;
import com.flowcortex.realtime.application.ReplayBuffer.Event;
import com.flowcortex.realtime.application.ReplayBuffer.Replay;
import com.flowcortex.realtime.application.StreamRegistry.Slot;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Holds this instance's open streams and fans received events out to them. Heartbeats, expiry and
 * every write run on the hub's own threads, never on a caller's or on Boot's shared scheduler, and
 * each stream is written in the order its events were received.
 */
@Component
public class SseHub implements ApplicationListener<ContextClosedEvent> {

    /** Whether a stream was taken on. */
    public enum Admission { OPENED, LIMIT_REACHED, CLOSED }

    private static final Logger LOG = LoggerFactory.getLogger(SseHub.class);

    private final Clock clock;
    private final ReplayBuffer buffer;
    private final StreamRegistry registry;
    private final int maxPendingFrames;
    private final ScheduledThreadPoolExecutor scheduler;
    // Authorizers may query a database, so rechecks never share the threads that write heartbeats and events.
    private final ScheduledThreadPoolExecutor rechecker;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    // Guarded by lock.
    private boolean closed;

    @Autowired
    public SseHub(RealtimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SseHub(RealtimeProperties properties, Clock clock) {
        this.clock = clock;
        String instanceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.buffer = new ReplayBuffer(instanceId, properties.replay().maxEvents(), properties.replay().maxAge(), clock);
        this.registry = new StreamRegistry(properties.maxStreamsPerUser());
        this.maxPendingFrames = properties.replay().maxEvents();
        AtomicInteger threadNumber = new AtomicInteger();
        this.scheduler = new ScheduledThreadPoolExecutor(properties.hubThreads(), task -> {
            Thread thread = new Thread(task, "realtime-hub-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
        long heartbeatMs = properties.heartbeat().toMillis();
        this.scheduler.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        this.rechecker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "realtime-recheck");
            thread.setDaemon(true);
            return thread;
        });
        long recheckMs = properties.recheckInterval().toMillis();
        this.rechecker.scheduleWithFixedDelay(this::recheck, recheckMs, recheckMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a stream on {@code sink}: admits it under the user's cap, answers {@code lastEventId}
     * with a replay or a resync, and ends it at {@code expiresAt}. A refused sink is left untouched,
     * except that a closed hub completes it.
     */
    public Admission register(UUID userId, Set<String> topics, Instant expiresAt, String lastEventId, StreamSink sink) {
        return register(userId, topics, Map.of(), expiresAt, lastEventId, sink);
    }

    /**
     * As above, and every {@code rechecks} entry (a topic of {@code topics} and the authorizer that
     * may withdraw access to it) is asked again each recheck interval.
     */
    public Admission register(UUID userId, Set<String> topics, Map<String, TopicAuthorizer> rechecks, Instant expiresAt,
            String lastEventId, StreamSink sink) {
        synchronized (lock) {
            if (!closed) {
                Optional<Slot> slot = registry.tryAdmit(userId);
                if (slot.isEmpty()) {
                    return Admission.LIMIT_REACHED;
                }
                Stream stream = new Stream(userId, topics, Map.copyOf(rechecks), sink, slot.get());
                sink.onClose(() -> stream.close(false));
                Replay replay = buffer.replay(lastEventId, topics);
                if (replay.kind() == ReplayBuffer.Kind.RESYNC) {
                    stream.enqueue(SseFrame.resync());
                }
                replay.events().forEach(event -> stream.enqueue(frame(event)));
                streams.add(stream);
                long untilExpiry = Math.max(0, Duration.between(clock.instant(), expiresAt).toMillis());
                stream.expiry = scheduler.schedule(() -> stream.close(true), untilExpiry, TimeUnit.MILLISECONDS);
                return Admission.OPENED;
            }
        }
        sink.complete();
        return Admission.CLOSED;
    }

    /** Takes in one event received from the broker and queues it for every stream subscribed to its topic. */
    public void onEvent(String topic, String type, String data) {
        List<Stream> overflowed = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            SseFrame frame = frame(buffer.append(topic, type, data));
            for (Stream stream : streams) {
                if (stream.topics.contains(topic) && !stream.enqueue(frame)) {
                    overflowed.add(stream);
                }
            }
        }
        overflowed.forEach(stream -> stream.close(true));
    }

    /** Tells every open stream to refetch: events may have been missed while the broker was away. */
    public void resync() {
        broadcast(SseFrame.resync());
    }

    private void heartbeat() {
        try {
            broadcast(SseFrame.ping());
        } catch (RuntimeException e) {
            LOG.warn("Realtime heartbeat failed", e);
        }
    }

    /**
     * Asks again about every revocable topic of every open stream and completes a stream whose access
     * was withdrawn. An authorizer that throws leaves its stream open until the next round.
     */
    private void recheck() {
        try {
            List<Stream> watched;
            synchronized (lock) {
                watched = streams.stream().filter(stream -> !stream.rechecks.isEmpty()).toList();
            }
            for (Stream stream : watched) {
                recheck(stream);
            }
        } catch (RuntimeException e) {
            LOG.warn("Realtime recheck failed", e);
        }
    }

    private void recheck(Stream stream) {
        for (Map.Entry<String, TopicAuthorizer> entry : stream.rechecks.entrySet()) {
            if (stream.ended.get()) {
                return;
            }
            boolean allowed;
            try {
                allowed = entry.getValue().authorize(stream.userId, entry.getKey());
            } catch (RuntimeException e) {
                LOG.warn("Realtime recheck of a {} topic failed, keeping the stream: {}", family(entry.getKey()), e.toString());
                continue;
            }
            if (!allowed) {
                LOG.info("realtime.stream outcome=revoked topic={}", family(entry.getKey()));
                completeOnScheduler(stream);
                return;
            }
        }
    }

    private void completeOnScheduler(Stream stream) {
        try {
            scheduler.execute(() -> stream.close(true));
        } catch (RejectedExecutionException shuttingDown) {
            stream.close(true);
        }
    }

    /** The topic without its last segment ({@code support:request:42} is {@code support:request}): no ids in logs. */
    private static String family(String topic) {
        int cut = topic.lastIndexOf(':');
        return cut > 0 ? topic.substring(0, cut) : topic;
    }

    private void broadcast(SseFrame frame) {
        List<Stream> overflowed = new ArrayList<>();
        synchronized (lock) {
            for (Stream stream : streams) {
                if (!stream.enqueue(frame)) {
                    overflowed.add(stream);
                }
            }
        }
        overflowed.forEach(stream -> stream.close(true));
    }

    private static SseFrame frame(Event event) {
        return SseFrame.event(event.id(), event.type(), event.data());
    }

    /** The context's own close, not a child's (the management server has one): complete everything first. */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        List<Stream> open;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            open = List.copyOf(streams);
        }
        rechecker.shutdownNow();
        open.forEach(stream -> stream.close(true));
        scheduler.shutdownNow();
    }

    @PreDestroy
    void destroy() {
        rechecker.shutdownNow();
        scheduler.shutdownNow();
    }

    private final class Stream {

        final UUID userId;
        final Set<String> topics;
        final Map<String, TopicAuthorizer> rechecks;
        final StreamSink sink;
        private final Slot slot;
        private final ConcurrentLinkedQueue<SseFrame> pending = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pendingCount = new AtomicInteger();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();
        volatile ScheduledFuture<?> expiry;

        Stream(UUID userId, Set<String> topics, Map<String, TopicAuthorizer> rechecks, StreamSink sink, Slot slot) {
            this.userId = userId;
            this.topics = topics;
            this.rechecks = rechecks;
            this.sink = sink;
            this.slot = slot;
        }

        /** Queues a frame; {@code false} when this stream is too far behind to keep up. */
        boolean enqueue(SseFrame frame) {
            if (ended.get()) {
                return true;
            }
            if (pendingCount.get() >= maxPendingFrames) {
                return false;
            }
            pending.add(frame);
            pendingCount.incrementAndGet();
            scheduleDrain();
            return true;
        }

        private void scheduleDrain() {
            if (draining.compareAndSet(false, true)) {
                try {
                    scheduler.execute(this::drain);
                } catch (RejectedExecutionException shuttingDown) {
                    draining.set(false);
                }
            }
        }

        private void drain() {
            try {
                SseFrame frame;
                while (!ended.get() && (frame = pending.poll()) != null) {
                    pendingCount.decrementAndGet();
                    try {
                        sink.send(frame);
                    } catch (IOException | RuntimeException e) {
                        LOG.debug("Realtime stream write failed, dropping the stream: {}", e.toString());
                        close(true);
                        return;
                    }
                }
            } finally {
                draining.set(false);
                if (!ended.get() && !pending.isEmpty()) {
                    scheduleDrain();
                }
            }
        }

        /** Idempotent; {@code completeSink} is false when the transport is what ended the stream. */
        void close(boolean completeSink) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            streams.remove(this);
            slot.release();
            ScheduledFuture<?> scheduled = expiry;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (completeSink) {
                try {
                    sink.complete();
                } catch (RuntimeException e) {
                    LOG.debug("Completing a realtime stream failed: {}", e.toString());
                }
            }
        }
    }
}
