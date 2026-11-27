package com.flowcortex.trend.purge;

import com.flowcortex.jobs.api.ClusterJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Removes templates deleted longer ago than the grace period: media first, then each filed asset
 * (forgotten on the row once deleted), then the row. A template that fails is released and retried by
 * a later pass; the others in the batch are unaffected. Runs on one instance at a time through the
 * cluster runner, so a pass never overlaps another. The store and effects come from the template
 * code; until both exist a pass does nothing.
 */
@Component
@EnableConfigurationProperties(TrendPurgeProperties.class)
public class TemplatePurgeJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(TemplatePurgeJob.class);
    private static final String ROW_NOT_DELETED = "row_not_deleted";

    private final ObjectProvider<TemplatePurgeStore> stores;
    private final ObjectProvider<TemplatePurgeEffects> effects;
    private final TrendPurgeProperties properties;
    private final Clock clock;
    private final AtomicBoolean inertWarned = new AtomicBoolean();

    TemplatePurgeJob(ObjectProvider<TemplatePurgeStore> stores, ObjectProvider<TemplatePurgeEffects> effects,
            TrendPurgeProperties properties, Clock clock) {
        requireAtMostOne(stores, TemplatePurgeStore.class);
        requireAtMostOne(effects, TemplatePurgeEffects.class);
        this.stores = stores;
        this.effects = effects;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "trend.template-purge";
    }

    @Override
    public Duration interval() {
        return properties.interval();
    }

    @Override
    public void run() {
        TemplatePurgeStore store = stores.getIfUnique();
        TemplatePurgeEffects storage = effects.getIfUnique();
        if (store == null || storage == null) {
            warnWhenHalfRegistered(store, storage);
            return;
        }
        Instant deletedBefore = Instant.now(clock).minus(properties.grace());
        for (PurgeCandidate candidate : store.listDue(deletedBefore, properties.batchSize())) {
            purge(store, storage, candidate);
        }
    }

    private void purge(TemplatePurgeStore store, TemplatePurgeEffects storage, PurgeCandidate candidate) {
        String templateId = candidate.templateId();
        String step = "claim";
        boolean claimed = false;
        try {
            if (!store.claim(templateId)) {
                return;
            }
            claimed = true;
            step = "media";
            int objects = storage.deleteMedia(templateId);
            step = "assets";
            for (String assetId : candidate.filedAssetIds().stream().sorted().toList()) {
                storage.deleteFiledAsset(assetId);
                store.forgetFiledAsset(templateId, assetId);
            }
            step = "row";
            if (!store.deleteRow(templateId)) {
                throw new RowNotDeleted();
            }
            LOG.info("trend.template.purged templateId={} objects={} assets={}", templateId, objects,
                    candidate.filedAssetIds().size());
        } catch (RuntimeException e) {
            logFailure(templateId, step, e);
            if (claimed) {
                release(store, templateId);
            }
        }
    }

    private void release(TemplatePurgeStore store, String templateId) {
        try {
            store.release(templateId);
        } catch (RuntimeException e) {
            logFailure(templateId, "release", e);
        }
    }

    /** Logs the code, never the exception: its message can hold an object key. */
    private static void logFailure(String templateId, String step, RuntimeException failure) {
        LOG.warn("trend.template.purge_failed templateId={} step={} errorCode={}", templateId, step,
                errorCode(failure));
    }

    private static String errorCode(RuntimeException failure) {
        if (failure instanceof TemplatePurgeFailure coded && coded.code() != null && !coded.code().isBlank()) {
            return coded.code();
        }
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    private void warnWhenHalfRegistered(TemplatePurgeStore store, TemplatePurgeEffects storage) {
        if (store == null && storage == null) {
            return;
        }
        if (inertWarned.compareAndSet(false, true)) {
            LOG.warn("trend.template.purge_inert reason={}", store == null ? "store_missing" : "effects_missing");
        }
    }

    private static void requireAtMostOne(ObjectProvider<?> provider, Class<?> port) {
        if (provider.stream().limit(2).count() > 1) {
            throw new IllegalStateException(
                    "More than one " + port.getSimpleName() + " bean is registered; exactly one may exist");
        }
    }

    private static final class RowNotDeleted extends RuntimeException implements TemplatePurgeFailure {

        RowNotDeleted() {
            super(null, null, false, false);
        }

        @Override
        public String code() {
            return ROW_NOT_DELETED;
        }
    }
}
