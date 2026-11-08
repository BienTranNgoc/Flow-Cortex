package com.flowcortex.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.realtime.application.RealtimeProperties.Publish;
import com.flowcortex.realtime.application.RealtimeProperties.Replay;
import com.flowcortex.realtime.application.SseHub.Admission;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

class SseHubTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Set<String> RUN_A = Set.of("run:a");
    private static final Instant FAR = Instant.now().plus(Duration.ofHours(1));

    private final UUID user = UUID.randomUUID();
    private SseHub hub;

    private SseHub hub(Duration heartbeat, int maxStreamsPerUser, int maxEvents) {
        RealtimeProperties properties = new RealtimeProperties(heartbeat, new Replay(maxEvents, Duration.ofSeconds(120)),
                maxStreamsPerUser, 8, 10_000, 2, new Publish(1000));
        hub = new SseHub(properties, Clock.systemUTC());
        return hub;
    }

    private SseHub hub() {
        return hub(Duration.ofHours(1), 5, 100);
    }

    @AfterEach
    void shutDown() {
        if (hub != null) {
            hub.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));
        }
    }

    @Test
    void fanOutWritesGoToSubscribedStreamsOnTheHubsOwnThreads() {
        SseHub hub = hub();
        RecordingSink subscribed = new RecordingSink();
        RecordingSink other = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, subscribed);
        hub.register(user, Set.of("run:b"), FAR, null, other);

        hub.onEvent("run:a", "run.updated", "{\"runId\":\"a\"}");

        await().atMost(WAIT).until(() -> subscribed.frames.size() == 1);
        SseFrame frame = subscribed.frames.get(0);
        assertThat(frame.event()).isEqualTo("run.updated");
        assertThat(frame.data()).isEqualTo("{\"runId\":\"a\"}");
        assertThat(frame.id()).matches("[0-9a-f]+:1");
        assertThat(subscribed.threads).allMatch(name -> name.startsWith("realtime-hub-"));
        assertThat(other.frames).isEmpty();
    }

    @Test
    void eventsReachAStreamInTheOrderTheyWereReceived() {
        SseHub hub = hub();
        RecordingSink sink = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, sink);

        for (int i = 1; i <= 50; i++) {
            hub.onEvent("run:a", "run.updated", String.valueOf(i));
        }

        await().atMost(WAIT).until(() -> sink.frames.size() == 50);
        for (int i = 0; i < 50; i++) {
            assertThat(sink.frames.get(i).data()).isEqualTo(String.valueOf(i + 1));
        }
    }

    @Test
    void heartbeatsAreWrittenOnTheHubsOwnScheduler() {
        SseHub hub = hub(Duration.ofMillis(50), 5, 100);
        RecordingSink sink = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, sink);

        await().atMost(WAIT).until(() -> sink.frames.stream().filter(frame -> "ping".equals(frame.comment())).count() >= 2);

        assertThat(sink.threads).allMatch(name -> name.startsWith("realtime-hub-"));
    }

    @Test
    void aStreamWhoseWriteFailsIsDroppedAndItsSlotReleased() {
        SseHub hub = hub(Duration.ofMillis(50), 1, 100);
        RecordingSink failing = new RecordingSink();
        failing.failWith = new IOException("broken pipe");
        assertThat(hub.register(user, RUN_A, FAR, null, failing)).isEqualTo(Admission.OPENED);

        await().atMost(WAIT).until(() -> hub.register(user, RUN_A, FAR, null, new RecordingSink()) == Admission.OPENED);
        assertThat(failing.completed.get()).isTrue();
    }

    @Test
    void aStreamBeyondTheUsersCapIsRefusedAndNeverWrittenTo() {
        SseHub hub = hub(Duration.ofMillis(50), 1, 100);
        assertThat(hub.register(user, RUN_A, FAR, null, new RecordingSink())).isEqualTo(Admission.OPENED);
        RecordingSink refused = new RecordingSink();

        assertThat(hub.register(user, RUN_A, FAR, null, refused)).isEqualTo(Admission.LIMIT_REACHED);

        hub.onEvent("run:a", "run.updated", "{}");
        assertThat(refused.frames).isEmpty();
        assertThat(refused.completed.get()).isFalse();
    }

    @Test
    void theTransportEndingAStreamReleasesItsSlot() {
        SseHub hub = hub(Duration.ofHours(1), 1, 100);
        RecordingSink first = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, first);
        assertThat(hub.register(user, RUN_A, FAR, null, new RecordingSink())).isEqualTo(Admission.LIMIT_REACHED);

        first.closeFromTransport();

        assertThat(hub.register(user, RUN_A, FAR, null, new RecordingSink())).isEqualTo(Admission.OPENED);
    }

    @Test
    void aStreamEndsAtTheTokensExpiry() {
        SseHub hub = hub(Duration.ofHours(1), 1, 100);
        RecordingSink sink = new RecordingSink();

        hub.register(user, RUN_A, Instant.now().plusMillis(150), null, sink);

        await().atMost(WAIT).untilTrue(sink.completed);
        assertThat(hub.register(user, RUN_A, FAR, null, new RecordingSink())).isEqualTo(Admission.OPENED);
    }

    @Test
    void contextClosedCompletesEveryStreamReleasesSlotsAndRefusesLaterRegistrations() {
        SseHub hub = hub(Duration.ofHours(1), 1, 100);
        RecordingSink one = new RecordingSink();
        RecordingSink two = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, one);
        hub.register(UUID.randomUUID(), RUN_A, FAR, null, two);

        hub.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));

        assertThat(one.completed.get()).isTrue();
        assertThat(two.completed.get()).isTrue();
        RecordingSink late = new RecordingSink();
        assertThat(hub.register(user, RUN_A, FAR, null, late)).isEqualTo(Admission.CLOSED);
        assertThat(late.completed.get()).isTrue();
        assertThat(late.frames).isEmpty();
    }

    @Test
    void aContextClosedOfAChildContextDoesNotCloseTheHub() {
        SseHub hub = hub();
        RecordingSink sink = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, sink);
        GenericApplicationContext child = new GenericApplicationContext(new GenericApplicationContext());

        hub.onApplicationEvent(new ContextClosedEvent(child));

        assertThat(sink.completed.get()).isFalse();
    }

    @Test
    void resyncReachesEveryOpenStreamOnce() {
        SseHub hub = hub();
        RecordingSink one = new RecordingSink();
        RecordingSink two = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, one);
        hub.register(user, Set.of("run:b"), FAR, null, two);

        hub.resync();

        await().atMost(WAIT).until(() -> one.frames.size() == 1 && two.frames.size() == 1);
        assertThat(one.frames.get(0).event()).isEqualTo("resync");
        assertThat(two.frames.get(0).event()).isEqualTo("resync");
        assertThat(one.frames.get(0).id()).isNull();
    }

    @Test
    void aReconnectOnTheSameInstanceReplaysWhatItMissedBeforeLiveEvents() {
        SseHub hub = hub();
        RecordingSink first = new RecordingSink();
        hub.register(user, RUN_A, FAR, null, first);
        hub.onEvent("run:a", "run.updated", "1");
        hub.onEvent("run:b", "run.updated", "other");
        hub.onEvent("run:a", "run.updated", "2");
        await().atMost(WAIT).until(() -> first.frames.size() == 2);
        String seen = first.frames.get(0).id();

        RecordingSink second = new RecordingSink();
        hub.register(user, RUN_A, FAR, seen, second);
        hub.onEvent("run:a", "run.updated", "3");

        await().atMost(WAIT).until(() -> second.frames.size() == 2);
        assertThat(second.frames).extracting(SseFrame::data).containsExactly("2", "3");
    }

    @Test
    void aReconnectNamingAnotherInstanceGetsOneResyncThenLiveEvents() {
        SseHub hub = hub();
        RecordingSink sink = new RecordingSink();
        hub.register(user, RUN_A, FAR, "elsewhere:7", sink);
        hub.onEvent("run:a", "run.updated", "1");

        await().atMost(WAIT).until(() -> sink.frames.size() == 2);
        assertThat(sink.frames).extracting(SseFrame::event).containsExactly("resync", "run.updated");
    }

    @Test
    void aStreamThatFallsFartherBehindThanTheReplayBoundIsCompleted() throws InterruptedException {
        SseHub hub = hub(Duration.ofHours(1), 5, 3);
        RecordingSink slow = new RecordingSink();
        slow.block = new CountDownLatch(1);
        hub.register(user, RUN_A, FAR, null, slow);

        for (int i = 0; i < 20; i++) {
            hub.onEvent("run:a", "run.updated", String.valueOf(i));
        }

        await().atMost(WAIT).untilTrue(slow.completed);
        slow.block.countDown();
    }

    private static final class RecordingSink implements StreamSink {

        final List<SseFrame> frames = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();
        volatile IOException failWith;
        volatile CountDownLatch block;
        private volatile Runnable onClose = () -> { };

        @Override
        public void send(SseFrame frame) throws IOException {
            threads.add(Thread.currentThread().getName());
            CountDownLatch latch = block;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failWith != null) {
                throw failWith;
            }
            frames.add(frame);
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public void onClose(Runnable callback) {
            this.onClose = callback;
        }

        void closeFromTransport() {
            closes.incrementAndGet();
            onClose.run();
        }
    }
}
