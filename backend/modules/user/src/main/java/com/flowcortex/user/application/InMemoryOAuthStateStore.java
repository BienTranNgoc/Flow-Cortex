package com.flowcortex.user.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Caffeine-backed in-memory store. Entries expire on read after
 * {@code stateTtl} (default 10 minutes). {@link #consume(String)} is
 * one-shot — the entry is evicted on read.
 */
@Component
public class InMemoryOAuthStateStore implements OAuthStateStore {

    private final Cache<String, OAuthStateRecord> cache;

    public InMemoryOAuthStateStore(GoogleOAuthProperties properties, Clock clock) {
        Duration ttl = properties.stateTtl();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(100_000)
                .build();
    }

    @Override
    public String put(OAuthStateRecord record) {
        String state = OAuthStateStore.Generator.newState();
        cache.put(state, record);
        return state;
    }

    @Override
    public Optional<OAuthStateRecord> consume(String state) {
        if (state == null || state.isEmpty()) {
            return Optional.empty();
        }
        OAuthStateRecord record = cache.getIfPresent(state);
        if (record != null) {
            cache.invalidate(state);
        }
        return Optional.ofNullable(record);
    }
}
