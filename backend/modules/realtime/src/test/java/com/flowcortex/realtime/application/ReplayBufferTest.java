package com.flowcortex.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.realtime.application.ReplayBuffer.Decision;
import com.flowcortex.realtime.application.ReplayBuffer.Event;
import com.flowcortex.realtime.application.ReplayBuffer.Kind;
import com.flowcortex.realtime.application.ReplayBuffer.Replay;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplayBufferTest {

    private static final String INSTANCE = "inst";
    private static final Set<String> RUN_A = Set.of("run:a");

    private final MutableClock clock = new MutableClock();

    private ReplayBuffer buffer(int maxEvents, Duration maxAge) {
        return new ReplayBuffer(INSTANCE, maxEvents, maxAge, clock);
    }

    private static void fill(ReplayBuffer buffer, int count) {
        for (int i = 0; i < count; i++) {
            buffer.append("run:a", "run.updated", "{\"n\":" + (i + 1) + "}");
        }
    }

    @Test
    void appendAssignsIncreasingSeqsUnderTheInstanceId() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));

        Event first = buffer.append("run:a", "run.updated", "{}");
        Event second = buffer.append("run:b", "run.updated", "{}");

        assertThat(first.id()).isEqualTo("inst:1");
        assertThat(second.id()).isEqualTo("inst:2");
        assertThat(second.seq()).isEqualTo(2);
    }

    @Test
    void noHeaderIsLive() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));
        fill(buffer, 3);

        assertThat(buffer.replay(null, RUN_A).kind()).isEqualTo(Kind.LIVE);
        assertThat(buffer.replay("  ", RUN_A).kind()).isEqualTo(Kind.LIVE);
    }

    @Test
    void sameInstanceInsideTheWindowReplaysOnlySubscribedTopicsInOrder() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));
        buffer.append("run:a", "run.updated", "1");
        buffer.append("run:a", "run.updated", "2");
        buffer.append("run:b", "run.updated", "3");
        buffer.append("run:a", "run.updated", "4");
        buffer.append("run:a", "run.updated", "5");

        Replay replay = buffer.replay("inst:2", RUN_A);

        assertThat(replay.kind()).isEqualTo(Kind.REPLAY);
        assertThat(replay.events()).extracting(Event::data).containsExactly("4", "5");
    }

    @Test
    void lastEqualToTheNewestReplaysNothing() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));
        fill(buffer, 3);

        Replay replay = buffer.replay("inst:3", RUN_A);

        assertThat(replay.kind()).isEqualTo(Kind.REPLAY);
        assertThat(replay.events()).isEmpty();
    }

    @Test
    void anotherInstanceResyncs() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));
        fill(buffer, 3);

        assertThat(buffer.replay("other:2", RUN_A).kind()).isEqualTo(Kind.RESYNC);
    }

    @Test
    void aSeqNewerThanTheNewestResyncs() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));
        fill(buffer, 3);

        assertThat(buffer.replay("inst:9", RUN_A).kind()).isEqualTo(Kind.RESYNC);
    }

    @Test
    void aMalformedIdResyncsAndNeverFails() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));
        fill(buffer, 3);

        for (String malformed : List.of("garbage", "inst:", "inst:abc", "inst:-1", ":2", "inst:2:3", "inst:99999999999999999999")) {
            assertThat(buffer.replay(malformed, RUN_A).kind()).as(malformed).isEqualTo(Kind.RESYNC);
        }
    }

    @Test
    void anEmptyBufferNamesNoSeqExceptTheLastOneIssued() {
        ReplayBuffer buffer = buffer(10, Duration.ofMinutes(2));

        assertThat(buffer.replay("inst:1", RUN_A).kind()).isEqualTo(Kind.RESYNC);
    }

    @Test
    void evictionByCountResyncsAnIdBeforeTheRemainingRange() {
        ReplayBuffer buffer = buffer(3, Duration.ofMinutes(2));
        fill(buffer, 5);

        assertThat(buffer.replay("inst:1", RUN_A).kind()).isEqualTo(Kind.RESYNC);
        assertThat(buffer.replay("inst:2", RUN_A).kind()).isEqualTo(Kind.RESYNC);
        Replay replay = buffer.replay("inst:3", RUN_A);
        assertThat(replay.kind()).isEqualTo(Kind.REPLAY);
        assertThat(replay.events()).extracting(Event::seq).containsExactly(4L, 5L);
    }

    @Test
    void evictionByAgeDropsOldEvents() {
        ReplayBuffer buffer = buffer(100, Duration.ofSeconds(120));
        fill(buffer, 2);
        clock.advance(Duration.ofSeconds(121));
        buffer.append("run:a", "run.updated", "3");

        assertThat(buffer.replay("inst:1", RUN_A).kind()).isEqualTo(Kind.RESYNC);
        assertThat(buffer.replay("inst:2", RUN_A).kind()).isEqualTo(Kind.RESYNC);
        assertThat(buffer.replay("inst:3", RUN_A).events()).isEmpty();
    }

    @Test
    void eventsAgeOutWhileNothingNewArrives() {
        ReplayBuffer buffer = buffer(100, Duration.ofSeconds(120));
        fill(buffer, 3);
        clock.advance(Duration.ofSeconds(121));

        assertThat(buffer.replay("inst:1", RUN_A).kind()).isEqualTo(Kind.RESYNC);
        assertThat(buffer.replay("inst:3", RUN_A).kind()).isEqualTo(Kind.REPLAY);
    }

    @Test
    void decideIsAPureFunctionOfTheIdAndTheRange() {
        assertThat(ReplayBuffer.decide(null, INSTANCE, 10, 15)).isEqualTo(new Decision(Kind.LIVE, 0));
        assertThat(ReplayBuffer.decide("inst:12", INSTANCE, 10, 15)).isEqualTo(new Decision(Kind.REPLAY, 12));
        assertThat(ReplayBuffer.decide("inst:15", INSTANCE, 10, 15)).isEqualTo(new Decision(Kind.REPLAY, 15));
        assertThat(ReplayBuffer.decide("inst:9", INSTANCE, 10, 15).kind()).isEqualTo(Kind.RESYNC);
        assertThat(ReplayBuffer.decide("inst:16", INSTANCE, 10, 15).kind()).isEqualTo(Kind.RESYNC);
        assertThat(ReplayBuffer.decide("elsewhere:12", INSTANCE, 10, 15).kind()).isEqualTo(Kind.RESYNC);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
