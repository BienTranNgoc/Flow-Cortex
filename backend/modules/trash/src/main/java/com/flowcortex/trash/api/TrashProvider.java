package com.flowcortex.trash.api;

/**
 * One kind of trashable item, implemented as a Spring bean by the module that owns it. Each answer is a conditional
 * write on the owner's own row ({@code WHERE id = :id AND owner = :owner AND deleted_at IS NOT NULL}), {@code Gone}
 * when it touches no row.
 *
 * <p>{@code restore} and {@code purge} run inside the Trash's transaction, which can still roll back after they
 * return. Effects outside the database (object-store deletes, outbound calls) happen only after commit, through
 * {@code TransactionSynchronization.afterCommit} or an event-outbox publication. Purged means the owning row is
 * deleted, nothing more.
 */
public interface TrashProvider {

    /** The item type key: 2 to 32 lowercase letters, digits or underscores, starting with a letter. */
    String type();

    /** Lower ranks are purged first by empty, purge and the expiry sweep. */
    int purgeRank();

    /** Whether the member is warned before the sweep deletes an expired entry of this type. */
    boolean remindsBeforeExpiry();

    /** Runs inside the Trash transaction with the entry locked. */
    RestoreOutcome restore(TrashItemRef item);

    /** Idempotent: a row already gone answers {@link PurgeOutcome.Gone}. Runs inside the Trash transaction. */
    PurgeOutcome purge(TrashItemRef item);
}
