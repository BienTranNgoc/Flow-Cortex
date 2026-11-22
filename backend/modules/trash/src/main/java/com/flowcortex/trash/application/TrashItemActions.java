package com.flowcortex.trash.application;

import com.flowcortex.trash.api.PurgeOutcome;
import com.flowcortex.trash.api.RestoreOutcome;
import com.flowcortex.trash.api.TrashItemRef;
import com.flowcortex.trash.api.TrashProvider;
import com.flowcortex.trash.domain.EmptyCursor;
import com.flowcortex.trash.domain.PurgeOrder;
import com.flowcortex.trash.domain.TrashRequestRules.ItemRef;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import com.flowcortex.trash.infrastructure.TrashEntryStore.OwnedEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restore, purge and empty on the caller's own entries. Each item is one transaction that locks its entry, asks the
 * provider and then drops or keeps the entry, so a provider write and the entry's removal stand or fall together and
 * a provider exception fails only that item.
 */
@Service
public class TrashItemActions {

    private static final Logger LOG = LoggerFactory.getLogger(TrashItemActions.class);
    private static final int EMPTY_CHUNK = 100;
    private static final int EMPTY_MAX_EXAMINED = 500;

    /** How one item ended. */
    public enum Status {
        /** The provider restored or purged the row, or found it gone on a purge; the entry is dropped. */
        SUCCEEDED,
        /** No entry of the caller's, no provider of the type, or a restore that found the row gone (entry dropped). */
        NOT_FOUND,
        /** The provider refused a restore; the entry stays. */
        CONFLICT,
        /** The provider refused a purge; the entry stays. */
        BLOCKED,
        /** The provider or the database threw; everything of the item was rolled back. */
        FAILED
    }

    /**
     * @param messageKey the provider's message code on {@code CONFLICT} and {@code BLOCKED}, else null
     * @param failure the exception on {@code FAILED}, else null
     */
    public record Result(Status status, String messageKey, RuntimeException failure) {

        static Result of(Status status) {
            return new Result(status, null, null);
        }
    }

    /**
     * @param next the position to resume from, empty when nothing lies after the last entry examined
     * @param remaining the entries of provided types the caller still holds after the run
     */
    public record EmptyResult(long purged, long blocked, long failed, long remaining, Optional<EmptyCursor> next) {
    }

    private final TrashEntryStore store;
    private final TrashProviders providers;
    private final TransactionTemplate transaction;

    TrashItemActions(TrashEntryStore store, TrashProviders providers, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.providers = providers;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Results in the order of {@code items}. */
    public List<Result> restoreMany(UUID ownerId, List<ItemRef> items) {
        return items.stream().map(item -> restore(ownerId, item)).toList();
    }

    /** Processed by purge rank, then request position; results in the order of {@code items}. */
    public List<Result> purgeMany(UUID ownerId, List<ItemRef> items) {
        Map<String, Integer> ranks = providers.rankByType();
        List<Integer> processing = PurgeOrder.byRank(IntStream.range(0, items.size()).boxed().toList(),
                position -> ranks.getOrDefault(items.get(position).type(), Integer.MAX_VALUE));
        Result[] results = new Result[items.size()];
        for (int position : processing) {
            results[position] = purge(ownerId, items.get(position));
        }
        return List.of(results);
    }

    public Result restore(UUID ownerId, ItemRef item) {
        return act(ownerId, item, "restore", (provider, entryId) -> {
            RestoreOutcome outcome = provider.restore(new TrashItemRef(item.type(), item.id(), ownerId));
            return switch (outcome) {
                case RestoreOutcome.Restored restored -> {
                    store.delete(entryId);
                    yield Result.of(Status.SUCCEEDED);
                }
                case RestoreOutcome.Gone gone -> {
                    store.delete(entryId);
                    yield Result.of(Status.NOT_FOUND);
                }
                case RestoreOutcome.Conflict conflict ->
                        new Result(Status.CONFLICT, requireMessage(conflict.messageKey(), item.type(), item.id()),
                                null);
            };
        });
    }

    public Result purge(UUID ownerId, ItemRef item) {
        return act(ownerId, item, "purge", (provider, entryId) ->
                settlePurge(provider, new TrashItemRef(item.type(), item.id(), ownerId), entryId));
    }

    /**
     * Purges one entry the expiry sweep picked, in its own transaction. The entry is locked with {@code SKIP LOCKED}
     * and re-checked against {@code cutoff} (and {@code remindedNoLaterThan} when given) under the lock, so one that
     * a restore, a purge or a re-record got to first is left alone. {@code NOT_FOUND} here means exactly that: the
     * entry was not purgeable when reached and stays as it is. No owner filter: the sweep acts for every account.
     */
    public Result purgeExpired(TrashProvider provider, UUID entryId, Instant cutoff,
            Optional<Instant> remindedNoLaterThan) {
        try {
            return transaction.execute(status -> store.lockExpired(entryId, cutoff, remindedNoLaterThan)
                    .map(locked -> {
                        Result result = settlePurge(provider,
                                new TrashItemRef(locked.type(), locked.itemId(), locked.ownerId()), entryId);
                        if (result.status() == Status.BLOCKED) {
                            LOG.info("trash.sweep blocked type={} entryId={}", locked.type(), entryId);
                        }
                        return result;
                    })
                    .orElseGet(() -> Result.of(Status.NOT_FOUND)));
        } catch (RuntimeException failure) {
            LOG.warn("trash.sweep failed type={} entryId={}", provider.type(), entryId, failure);
            return new Result(Status.FAILED, null, failure);
        }
    }

    private Result settlePurge(TrashProvider provider, TrashItemRef ref, UUID entryId) {
        PurgeOutcome outcome = provider.purge(ref);
        return switch (outcome) {
            case PurgeOutcome.Purged purged -> {
                store.delete(entryId);
                yield Result.of(Status.SUCCEEDED);
            }
            case PurgeOutcome.Gone gone -> {
                store.delete(entryId);
                yield Result.of(Status.SUCCEEDED);
            }
            case PurgeOutcome.Blocked blocked ->
                    new Result(Status.BLOCKED, requireMessage(blocked.messageKey(), ref.type(), ref.itemId()), null);
        };
    }

    /**
     * Purges the caller's entries of provided types by rank, delete time and id, strictly after {@code after}, and
     * examines at most 500. Blocked and failed entries stay and are not looked at again by the same run, because the
     * run only moves forward.
     */
    public EmptyResult empty(UUID ownerId, Optional<EmptyCursor> after) {
        Map<String, Integer> ranks = providers.rankByType();
        long purged = 0;
        long blocked = 0;
        long failed = 0;
        int examined = 0;
        boolean exhausted = false;
        Optional<EmptyCursor> position = after;
        while (!exhausted && examined < EMPTY_MAX_EXAMINED) {
            int size = Math.min(EMPTY_CHUNK, EMPTY_MAX_EXAMINED - examined);
            List<OwnedEntry> chunk = store.ownedByRankAfter(ownerId, ranks, position, size);
            for (OwnedEntry entry : chunk) {
                Status status = purge(ownerId, new ItemRef(entry.type(), entry.itemId())).status();
                if (status == Status.SUCCEEDED) {
                    purged++;
                } else if (status == Status.BLOCKED) {
                    blocked++;
                } else if (status == Status.FAILED) {
                    failed++;
                }
            }
            examined += chunk.size();
            exhausted = chunk.size() < size;
            if (!chunk.isEmpty()) {
                position = Optional.of(chunk.getLast().position());
            }
        }
        boolean moreAfter = !exhausted && !store.ownedByRankAfter(ownerId, ranks, position, 1).isEmpty();
        return new EmptyResult(purged, blocked, failed, store.countOwned(ownerId, ranks.keySet()),
                moreAfter ? position : Optional.empty());
    }

    private Result act(UUID ownerId, ItemRef item, String action, BiFunction<TrashProvider, UUID, Result> body) {
        Optional<TrashProvider> provider = providers.find(item.type());
        if (provider.isEmpty()) {
            return Result.of(Status.NOT_FOUND);
        }
        try {
            return transaction.execute(status -> store.lockOwned(item.type(), item.id(), ownerId)
                    .map(entryId -> body.apply(provider.get(), entryId))
                    .orElseGet(() -> Result.of(Status.NOT_FOUND)));
        } catch (RuntimeException failure) {
            LOG.warn("Trash {} of {}:{} failed", action, item.type(), item.id(), failure);
            return new Result(Status.FAILED, null, failure);
        }
    }

    private static String requireMessage(String messageKey, String type, String itemId) {
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalStateException("The provider of " + type + " refused " + itemId
                    + " without a message key");
        }
        return messageKey;
    }
}
