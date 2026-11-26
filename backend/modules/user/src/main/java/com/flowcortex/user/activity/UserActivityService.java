package com.flowcortex.user.activity;

import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.UserActivityItem;
import com.flowcortex.user.api.UserActivitySource;
import com.flowcortex.user.api.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One member's activity across every module that contributes a {@link UserActivitySource}. The caller must be
 * able to see the account at all; each source then supplies {@code limit + 1} items after the cursor and
 * {@link ActivityMerge} cuts the page.
 */
@Service
class UserActivityService {

    /** {@code nextCursor} is {@code null} on the last page. */
    record ActivityPage(List<UserActivityItem> items, String nextCursor) {
    }

    private final UserService userService;
    private final List<UserActivitySource> sources;

    UserActivityService(UserService userService, List<UserActivitySource> sources) {
        this.userService = userService;
        this.sources = List.copyOf(sources);
    }

    /** @throws com.flowcortex.shared.web.NotFoundException if the account is outside the caller's owner scope */
    @Transactional(readOnly = true)
    ActivityPage page(UUID callerId, UUID accountId, ActivityCursor after, int limit) {
        userService.getUser(callerId, accountId);
        List<List<UserActivityItem>> perSource =
                sources.stream().map(source -> source.page(accountId, after, limit + 1)).toList();
        ActivityMerge.Page merged = ActivityMerge.page(perSource, limit);
        return new ActivityPage(merged.items(), merged.next() == null ? null : merged.next().encode());
    }
}
