package com.flowcortex.content.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UnresolvedLegalItemsTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final UnresolvedLegalItems items = new UnresolvedLegalItems(ticker);

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    @Test
    void warnsOncePerSlugWithinTenSeconds() {
        assertThat(items.shouldWarn("legal-pricing")).isTrue();

        advance(Duration.ofSeconds(9));

        assertThat(items.shouldWarn("legal-pricing")).isFalse();
    }

    @Test
    void warnsAgainAfterTenSeconds() {
        assertThat(items.shouldWarn("legal-pricing")).isTrue();

        advance(Duration.ofSeconds(10).plusMillis(1));

        assertThat(items.shouldWarn("legal-pricing")).isTrue();
        assertThat(items.shouldWarn("legal-pricing")).isFalse();
    }

    @Test
    void slugsAreThrottledIndependently() {
        assertThat(items.shouldWarn("legal-pricing")).isTrue();
        assertThat(items.shouldWarn("legal-terms")).isTrue();
        assertThat(items.shouldWarn("legal-pricing")).isFalse();
        assertThat(items.shouldWarn("legal-terms")).isFalse();
    }
}
