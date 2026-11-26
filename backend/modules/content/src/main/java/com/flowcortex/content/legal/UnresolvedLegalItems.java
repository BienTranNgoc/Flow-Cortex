package com.flowcortex.content.legal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Throttles the warning about an index item that could not be resolved: the route is read on every signed-in
 * page view, so each slug is reported at most once per ten seconds per instance.
 */
@Component
public class UnresolvedLegalItems {

    static final Duration WINDOW = Duration.ofSeconds(10);
    static final int MAX_SLUGS = 100;

    private final Cache<String, Boolean> recentlyReported;

    @Autowired
    public UnresolvedLegalItems() {
        this(Ticker.systemTicker());
    }

    UnresolvedLegalItems(Ticker ticker) {
        this.recentlyReported = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(MAX_SLUGS)
                .ticker(ticker)
                .build();
    }

    /** True when the slug was not reported within the last ten seconds; the call counts as a report. */
    public boolean shouldWarn(String slug) {
        return recentlyReported.asMap().putIfAbsent(slug, Boolean.TRUE) == null;
    }
}
