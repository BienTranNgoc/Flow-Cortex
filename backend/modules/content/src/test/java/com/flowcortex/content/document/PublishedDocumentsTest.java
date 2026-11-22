package com.flowcortex.content.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.document.PublishedDocuments.PublishedCopy;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class PublishedDocumentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final AtomicInteger loads = new AtomicInteger();
    private Optional<PublishedCopy> stored = Optional.empty();
    private final PublishedDocuments cache = new PublishedDocuments(slug -> {
        loads.incrementAndGet();
        return stored;
    }, ticker);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static PublishedCopy copy(int version) {
        return new PublishedCopy("terms", version, Instant.parse("2026-11-11T00:00:00Z"),
                JSON.createArrayNode(), JSON.createObjectNode());
    }

    @Test
    void cachesAnAbsentDocument() {
        assertThat(cache.find("terms")).isEmpty();
        assertThat(cache.find("terms")).isEmpty();

        assertThat(loads).hasValue(1);
    }

    @Test
    void servesTheSameCopyWithinTheTtl() {
        stored = Optional.of(copy(1));
        assertThat(cache.find("terms").orElseThrow().version()).isEqualTo(1);

        stored = Optional.of(copy(2));
        nanos.addAndGet(Duration.ofSeconds(9).toNanos());

        assertThat(cache.find("terms").orElseThrow().version()).isEqualTo(1);
        assertThat(loads).hasValue(1);
    }

    @Test
    void rereadsAfterTheTtl() {
        stored = Optional.of(copy(1));
        cache.find("terms");

        stored = Optional.of(copy(2));
        nanos.addAndGet(Duration.ofSeconds(10).toNanos() + 1);

        assertThat(cache.find("terms").orElseThrow().version()).isEqualTo(2);
        assertThat(loads).hasValue(2);
    }

    @Test
    void invalidationAfterCommitDropsTheSlug() {
        stored = Optional.of(copy(1));
        cache.find("terms");
        TransactionSynchronizationManager.initSynchronization();

        stored = Optional.of(copy(2));
        cache.invalidateAfterCommit("terms");

        assertThat(cache.find("terms").orElseThrow().version()).isEqualTo(1);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(cache.find("terms").orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void invalidationWithoutATransactionDropsTheSlugAtOnce() {
        stored = Optional.of(copy(1));
        cache.find("terms");

        stored = Optional.of(copy(2));
        cache.invalidateAfterCommit("terms");

        assertThat(cache.find("terms").orElseThrow().version()).isEqualTo(2);
    }
}
