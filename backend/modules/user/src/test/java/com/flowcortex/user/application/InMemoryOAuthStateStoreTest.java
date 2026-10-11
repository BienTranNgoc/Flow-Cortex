package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemoryOAuthStateStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private static final GoogleOAuthProperties PROPS = new GoogleOAuthProperties("id", "secret", java.util.List.of(),
            Duration.ofMinutes(10), true);

    private final InMemoryOAuthStateStore store = new InMemoryOAuthStateStore(PROPS, FIXED);

    @Test
    void putReturnsANonEmptyState() {
        String state = store.put(new OAuthStateRecord("http://localhost/cb", Instant.now(FIXED), "1.2.3.4"));
        assertThat(state).isNotEmpty();
        assertThat(state).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void putThenConsumeReturnsTheRecord() {
        OAuthStateRecord record = new OAuthStateRecord("http://localhost/cb", Instant.now(FIXED), "1.2.3.4");
        String state = store.put(record);

        Optional<OAuthStateRecord> consumed = store.consume(state);

        assertThat(consumed).hasValueSatisfying(r -> {
            assertThat(r.redirectUri()).isEqualTo("http://localhost/cb");
            assertThat(r.ip()).isEqualTo("1.2.3.4");
        });
    }

    @Test
    void consumeIsOneShot() {
        String state = store.put(new OAuthStateRecord("cb", Instant.now(FIXED), "ip"));
        assertThat(store.consume(state)).isPresent();
        assertThat(store.consume(state)).isEmpty();
    }

    @Test
    void consumeUnknownStateReturnsEmpty() {
        assertThat(store.consume("never-seen-state")).isEmpty();
    }

    @Test
    void consumeNullOrEmptyReturnsEmpty() {
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
    }

    @Test
    void putProducesDistinctStates() {
        String a = store.put(new OAuthStateRecord("cb", Instant.now(FIXED), "ip"));
        String b = store.put(new OAuthStateRecord("cb", Instant.now(FIXED), "ip"));
        assertThat(a).isNotEqualTo(b);
    }
}
