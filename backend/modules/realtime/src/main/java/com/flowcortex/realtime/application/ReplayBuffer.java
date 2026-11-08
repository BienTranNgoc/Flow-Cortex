package com.flowcortex.realtime.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The events this instance received recently, bounded by count and age, so a stream that
 * reconnects here can resume. Ids are {@code <instanceId>:<seq>}; another instance's id cannot
 * be placed in this buffer's order, so it resyncs.
 */
public final class ReplayBuffer {

    /** What to do for a reconnecting client. */
    public enum Kind { LIVE, REPLAY, RESYNC }

    /** A received event; {@code id} is {@code <instanceId>:<seq>}. */
    public record Event(String id, long seq, String topic, String type, String data) {
    }

    /** The verdict of {@link #decide}: for {@link Kind#REPLAY}, every event after {@code afterSeq}. */
    public record Decision(Kind kind, long afterSeq) {
    }

    /** The answer for one stream: the matching events to send first, or a resync. */
    public record Replay(Kind kind, List<Event> events) {
    }

    private record Stamped(Event event, Instant receivedAt) {
    }

    private final String instanceId;
    private final int maxEvents;
    private final Duration maxAge;
    private final Clock clock;
    private final ArrayDeque<Stamped> ring = new ArrayDeque<>();
    private long lastSeq;

    public ReplayBuffer(String instanceId, int maxEvents, Duration maxAge, Clock clock) {
        this.instanceId = instanceId;
        this.maxEvents = maxEvents;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * Pure verdict for a {@code Last-Event-ID} against the buffered range {@code [oldestSeq, newestSeq]}.
     * An empty buffer passes {@code newestSeq + 1} as the oldest.
     */
    public static Decision decide(String lastEventId, String instanceId, long oldestSeq, long newestSeq) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return new Decision(Kind.LIVE, 0);
        }
        Decision resync = new Decision(Kind.RESYNC, 0);
        int colon = lastEventId.lastIndexOf(':');
        if (colon <= 0 || colon == lastEventId.length() - 1 || !instanceId.equals(lastEventId.substring(0, colon))) {
            return resync;
        }
        long seq = parseSeq(lastEventId.substring(colon + 1));
        if (seq < 1) {
            return resync;
        }
        if (seq == newestSeq) {
            return new Decision(Kind.REPLAY, seq);
        }
        return seq < oldestSeq || seq > newestSeq ? resync : new Decision(Kind.REPLAY, seq);
    }

    private static long parseSeq(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return -1;
            }
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }

    public synchronized Event append(String topic, String type, String data) {
        evict();
        long seq = ++lastSeq;
        Event event = new Event(instanceId + ":" + seq, seq, topic, type, data);
        ring.addLast(new Stamped(event, clock.instant()));
        evict();
        return event;
    }

    public synchronized Replay replay(String lastEventId, Set<String> topics) {
        evict();
        long oldest = ring.isEmpty() ? lastSeq + 1 : ring.peekFirst().event().seq();
        Decision decision = decide(lastEventId, instanceId, oldest, lastSeq);
        if (decision.kind() != Kind.REPLAY) {
            return new Replay(decision.kind(), List.of());
        }
        List<Event> missed = new ArrayList<>();
        for (Stamped stamped : ring) {
            Event event = stamped.event();
            if (event.seq() > decision.afterSeq() && topics.contains(event.topic())) {
                missed.add(event);
            }
        }
        return new Replay(Kind.REPLAY, missed);
    }

    private void evict() {
        Instant cutoff = clock.instant().minus(maxAge);
        while (!ring.isEmpty() && (ring.size() > maxEvents || ring.peekFirst().receivedAt().isBefore(cutoff))) {
            ring.removeFirst();
        }
    }
}
