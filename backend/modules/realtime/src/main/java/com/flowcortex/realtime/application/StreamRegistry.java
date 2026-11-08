package com.flowcortex.realtime.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Counts the open streams of each user on this instance and refuses past the cap. State is local:
 * a user's streams on another instance do not count here.
 */
public final class StreamRegistry {

    /** One admitted stream's claim on its user's cap; releasing it twice frees one place. */
    public interface Slot {
        void release();
    }

    private final int maxStreamsPerUser;
    private final Map<UUID, Integer> open = new ConcurrentHashMap<>();

    public StreamRegistry(int maxStreamsPerUser) {
        this.maxStreamsPerUser = maxStreamsPerUser;
    }

    public Optional<Slot> tryAdmit(UUID userId) {
        boolean[] admitted = {false};
        open.compute(userId, (id, count) -> {
            int current = count == null ? 0 : count;
            if (current >= maxStreamsPerUser) {
                return count;
            }
            admitted[0] = true;
            return current + 1;
        });
        if (!admitted[0]) {
            return Optional.empty();
        }
        AtomicBoolean released = new AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) {
                open.compute(userId, (id, count) -> count == null || count <= 1 ? null : count - 1);
            }
        });
    }
}
