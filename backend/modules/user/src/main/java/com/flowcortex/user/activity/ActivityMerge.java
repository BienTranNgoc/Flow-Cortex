package com.flowcortex.user.activity;

import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.UserActivityItem;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Merges what every source returned into one page. Pure: each source passes up to {@code limit + 1} items after
 * the cursor, so more than {@code limit} items in all means a page follows.
 */
final class ActivityMerge {

    /**
     * Time descending, source ascending, id descending. Ids compare by their lower-case canonical text, which is
     * the unsigned byte order Postgres sorts uuids in; {@code UUID.compareTo} is signed and would disagree.
     */
    static final Comparator<UserActivityItem> ORDER = Comparator
            .comparing(UserActivityItem::occurredAt, Comparator.reverseOrder())
            .thenComparing(UserActivityItem::source)
            .thenComparing(item -> item.id().toString(), Comparator.reverseOrder());

    /** {@code next} is {@code null} when no item follows. */
    record Page(List<UserActivityItem> items, ActivityCursor next) {
    }

    private ActivityMerge() {
    }

    static Page page(Collection<? extends List<UserActivityItem>> perSource, int limit) {
        List<UserActivityItem> all = perSource.stream().flatMap(List::stream).sorted(ORDER).toList();
        if (all.size() <= limit) {
            return new Page(all, null);
        }
        List<UserActivityItem> items = all.subList(0, limit);
        UserActivityItem last = items.get(limit - 1);
        return new Page(items, new ActivityCursor(last.occurredAt(), last.source(), last.id()));
    }
}
