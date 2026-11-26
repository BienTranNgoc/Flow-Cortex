package com.flowcortex.trash.api;

/** The index of soft-deleted items. Both calls run inside the caller's open transaction and refuse to run outside one. */
public interface TrashBin {

    /**
     * Joins the caller's open transaction; {@link IllegalStateException} when none is active. Recording an item
     * that already has an entry refreshes it: it gets the full retention and a fresh reminder.
     *
     * @throws IllegalArgumentException when the type, item id or location code is malformed or an account is null
     */
    void record(TrashRecord record);

    /**
     * Drops the item's entry, if any, inside the caller's open transaction. An owning module MUST call it in every
     * transaction that un-trashes or hard-deletes a trashed row by a path other than this Trash's provider calls.
     *
     * @throws IllegalStateException when no transaction is active
     */
    void forget(String type, String itemId);
}
