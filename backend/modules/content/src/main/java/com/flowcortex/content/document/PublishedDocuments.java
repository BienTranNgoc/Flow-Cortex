package com.flowcortex.content.document;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published copy of each slug, cached for ten seconds (absence included) so anonymous page views do not
 * reach the database. The instance that publishes drops the slug after commit; the others converge by expiry.
 */
@Component
public class PublishedDocuments {

    static final Duration TTL = Duration.ofSeconds(10);
    static final int MAX_SLUGS = 500;

    /** The published slug, version, publication time, sections and SEO text. */
    public record PublishedCopy(String slug, int version, Instant publishedAt, JsonNode sections, JsonNode seoMeta) {

        static PublishedCopy of(ContentDocument published, JsonMapper json) {
            return new PublishedCopy(published.slug(), published.version(), published.publishedAt(),
                    json.readTree(published.sections()), json.readTree(published.seoMeta()));
        }
    }

    private final Cache<String, Optional<PublishedCopy>> cache;
    private final Function<String, Optional<PublishedCopy>> loader;

    @Autowired
    PublishedDocuments(ContentDocumentRepository documents, JsonMapper json) {
        this(slug -> documents.findBySlugAndStatus(slug, ContentDocument.PUBLISHED)
                .map(published -> PublishedCopy.of(published, json)), Ticker.systemTicker());
    }

    PublishedDocuments(Function<String, Optional<PublishedCopy>> loader, Ticker ticker) {
        this.loader = loader;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_SLUGS)
                .ticker(ticker)
                .build();
    }

    /** The published copy, or empty when the slug was never published. */
    public Optional<PublishedCopy> find(String slug) {
        return cache.get(slug, loader);
    }

    public void invalidate(String slug) {
        cache.invalidate(slug);
    }

    /** Drops the slug once the surrounding transaction commits, or at once when there is none. */
    void invalidateAfterCommit(String slug) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(slug);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(slug);
            }
        });
    }
}
