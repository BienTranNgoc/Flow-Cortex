package com.flowcortex.space.api;

import java.util.UUID;
import java.util.function.Supplier;

/** Membership-based access decision for a space; the user's system role is never an input. */
public interface SpaceAccess {

    /** True only when the user holds a role in the space whose granted actions include {@code action}; false for any null argument. */
    boolean can(UUID userId, SpaceAction action, UUID spaceId);

    /**
     * Returns when {@link #can} holds; otherwise throws exactly the instance {@code notFound} supplies.
     * The supplier must produce an exception the web layer maps to 404 (the shared {@code NotFoundException}
     * or a module's own NOT_FOUND problem), so a missing and a foreign space look the same.
     */
    void assertCan(UUID userId, SpaceAction action, UUID spaceId, Supplier<? extends RuntimeException> notFound);
}
