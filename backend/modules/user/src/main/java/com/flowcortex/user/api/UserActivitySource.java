package com.flowcortex.user.api;

import java.util.List;
import java.util.UUID;

/**
 * A module's contribution to a member's activity timeline. Implemented as a Spring bean by every module that
 * records something a member did or that was done to them; the user module asks each for one page and merges.
 */
public interface UserActivitySource {

    /** Stable lower-case name; sources are ordered by it when items share an instant. */
    String source();

    /**
     * Up to {@code limit} items of {@code accountId} after {@code after} ({@code null} for the first page),
     * ordered by time descending then id descending, read through an index on the account and time.
     */
    List<UserActivityItem> page(UUID accountId, ActivityCursor after, int limit);
}
