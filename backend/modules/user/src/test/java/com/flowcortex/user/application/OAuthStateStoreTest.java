package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OAuthStateStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void generateStateIsAtLeast32BytesBase64Url() {
        String state = OAuthStateStore.Generator.newState();
        assertThat(state).isNotEmpty();
        assertThat(state.length()).isGreaterThanOrEqualTo(43);
        assertThat(state).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void putThenConsumeReturnsRecord() {
        InMemoryOAuthStateStore store = newStore();
        OAuthStateRecord record = new OAuthStateRecord("http://localhost:5173/cb", Instant.now(FIXED), "1.2.3.4");
        String state = store.put(record);
        assertThat(store.consume(state)).hasValueSatisfying(r -> {
            assertThat(r.redirectUri()).isEqualTo("http://localhost:5173/cb");
            assertThat(r.ip()).isEqualTo("1.2.3.4");
        });
    }

    @Test
    void consumeTwiceReturnsEmpty() {
        InMemoryOAuthStateStore store = newStore();
        OAuthStateRecord record = new OAuthStateRecord("cb", Instant.now(FIXED), "ip");
        String state = store.put(record);
        assertThat(store.consume(state)).isPresent();
        assertThat(store.consume(state)).isEmpty();
    }

    @Test
    void consumeEmptyOrNullReturnsEmpty() {
        InMemoryOAuthStateStore store = newStore();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
    }

    @Test
    void consumeUnknownStateReturnsEmpty() {
        InMemoryOAuthStateStore store = newStore();
        assertThat(store.consume("never-seen")).isEmpty();
    }

    private static InMemoryOAuthStateStore newStore() {
        GoogleOAuthProperties props = new GoogleOAuthProperties("id", "secret", java.util.List.of(),
                Duration.ofMinutes(10), true);
        return new InMemoryOAuthStateStore(props, FIXED);
    }

    @SuppressWarnings("unused")
    private static void unusedToSilenceCompiler(AppUserRepository a, RefreshTokenRepository b) {
        Mockito.mock(AppUserRepository.class);
        Mockito.mock(RefreshTokenRepository.class);
        assertThatThrownBy(() -> {
        }).isNotNull();
    }
}
