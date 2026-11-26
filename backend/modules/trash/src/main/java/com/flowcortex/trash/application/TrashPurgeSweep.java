package com.flowcortex.trash.application;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.trash.api.TrashProvider;
import com.flowcortex.trash.application.TrashItemActions.Result;
import com.flowcortex.trash.domain.TrashClock;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import com.flowcortex.trash.infrastructure.TrashEntryStore.ExpiredEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Purges, through their providers, the entries older than the retention: by rank, then oldest first, one entry per
 * transaction. A pass is bounded in batches and in time and takes the rest next time; it returns at once when the
 * switch is off. An entry of a reminding type is purged only once its reminder is 24 hours old, and an entry whose
 * type has no provider is left alone and only counted.
 */
@Component
public class TrashPurgeSweep implements ClusterJob {

    /**
     * What bounds a pass.
     *
     * @param batchSize entries picked per select
     * @param maxBatches selects a pass may work through
     * @param budget how long a pass may run before it stops after the entry in hand
     */
    public record Limits(int batchSize, int maxBatches, Duration budget) {

        static final Limits DEFAULT = new Limits(200, 50, Duration.ofMinutes(20));

        public Limits {
            if (batchSize < 1 || maxBatches < 1 || budget.isNegative()) {
                throw new IllegalArgumentException("The sweep needs a positive batch size and batch cap and a budget "
                        + "of zero or more, got " + batchSize + ", " + maxBatches + ", " + budget);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TrashPurgeSweep.class);
    /** The pass budget stays under it, so a pass never outlives the claim it runs under. */
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final TrashEntryStore store;
    private final TrashProviders providers;
    private final TrashItemActions actions;
    private final TrashProperties properties;
    private final Clock clock;
    private final Limits limits;

    @Autowired
    TrashPurgeSweep(TrashEntryStore store, TrashProviders providers, TrashItemActions actions,
            TrashProperties properties, Clock clock) {
        this(store, providers, actions, properties, clock, Limits.DEFAULT);
    }

    public TrashPurgeSweep(TrashEntryStore store, TrashProviders providers, TrashItemActions actions,
            TrashProperties properties, Clock clock, Limits limits) {
        this.store = store;
        this.providers = providers;
        this.actions = actions;
        this.properties = properties;
        this.clock = clock;
        this.limits = limits;
    }

    @Override
    public String name() {
        return "trash.purge-sweep";
    }

    @Override
    public Duration interval() {
        return properties.sweepInterval();
    }

    @Override
    public Duration lease() {
        return LEASE;
    }

    @Override
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        Instant start = clock.instant();
        Instant cutoff = TrashClock.sweepCutoff(start, properties.retention());
        // The same test as TrashClock.remindedLongEnoughForPurge, as a bound the select and the lock can apply.
        Instant remindedNoLaterThan = start.minus(TrashClock.reminderGrace());
        Pass pass = new Pass(start.plus(limits.budget()));
        for (TrashProvider provider : providers.ranked()) {
            Optional<Instant> reminded = provider.remindsBeforeExpiry() ? Optional.of(remindedNoLaterThan)
                    : Optional.empty();
            if (!sweepType(provider, cutoff, reminded, pass)) {
                break;
            }
        }
        long withoutProvider = store.countExpiredOutside(providers.types(), cutoff);
        long awaitingReminder = store.countExpiredUnreminded(providers.remindingTypes(), cutoff);
        LOG.info("trash.sweep purged={} blocked={} failed={} withoutProvider={} awaitingReminder={} ceilingHit={}",
                pass.purged, pass.blocked, pass.failed, withoutProvider, awaitingReminder, pass.ceilingHit);
    }

    /**
     * Walks the type's expired entries oldest first in batches, each batch strictly after the last one, so an entry
     * that stayed (blocked, failed, locked) is not picked again by this pass and cannot fill every batch.
     *
     * @return false when a cap stopped the pass
     */
    private boolean sweepType(TrashProvider provider, Instant cutoff, Optional<Instant> reminded, Pass pass) {
        Optional<ExpiredEntry> after = Optional.empty();
        while (true) {
            List<ExpiredEntry> batch = store.expiredBatch(provider.type(), cutoff, reminded, after,
                    limits.batchSize());
            if (batch.isEmpty()) {
                return true;
            }
            if (pass.batches >= limits.maxBatches()) {
                pass.ceilingHit = true;
                return false;
            }
            pass.batches++;
            for (ExpiredEntry entry : batch) {
                pass.count(actions.purgeExpired(provider, entry.id(), cutoff, reminded));
                if (!clock.instant().isBefore(pass.deadline)) {
                    pass.ceilingHit = true;
                    return false;
                }
            }
            if (batch.size() < limits.batchSize()) {
                return true;
            }
            after = Optional.of(batch.getLast());
        }
    }

    private static final class Pass {

        private final Instant deadline;
        private int batches;
        private long purged;
        private long blocked;
        private long failed;
        private boolean ceilingHit;

        private Pass(Instant deadline) {
            this.deadline = deadline;
        }

        private void count(Result result) {
            switch (result.status()) {
                case SUCCEEDED -> purged++;
                case BLOCKED -> blocked++;
                case FAILED -> failed++;
                default -> {
                    // Not purgeable when reached (re-recorded, locked by another action): left for the next pass.
                }
            }
        }
    }
}
