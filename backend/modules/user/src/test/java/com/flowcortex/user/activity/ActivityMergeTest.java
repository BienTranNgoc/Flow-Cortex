package com.flowcortex.user.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.activity.ActivityMerge.Page;
import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.UserActivityItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityMergeTest {

    private static final Instant T = Instant.parse("2026-11-24T10:00:00.000000Z");
    private static final List<String> SOURCES =
            List.of("admin_action", "auth", "support_request", "workflow", "workflow_run");

    private static UserActivityItem item(String source, Instant at, String id) {
        return new UserActivityItem(UUID.fromString(id), source, source + ".x", "user", null, null, null, null,
                null, at);
    }

    private static UUID id(int n) {
        return new UUID(0L, n);
    }

    private static List<UUID> ids(Page page) {
        return page.items().stream().map(UserActivityItem::id).toList();
    }

    @Test
    void tiesAcrossSourcesAtAPageBoundary() {
        UserActivityItem workflow = item("workflow", T, id(1).toString());
        UserActivityItem run = item("workflow_run", T, id(2).toString());
        UserActivityItem auth = item("auth", T.minusSeconds(60), id(3).toString());

        Page first = ActivityMerge.page(List.of(List.of(auth), List.of(workflow), List.of(run)), 1);
        assertThat(first.items()).containsExactly(workflow);
        assertThat(first.next()).isEqualTo(new ActivityCursor(T, "workflow", workflow.id()));

        Page second = ActivityMerge.page(List.of(List.of(auth), List.of(), List.of(run)), 1);
        assertThat(second.items()).containsExactly(run);
        assertThat(second.next()).isEqualTo(new ActivityCursor(T, "workflow_run", run.id()));

        Page third = ActivityMerge.page(List.of(List.of(auth)), 1);
        assertThat(third.items()).containsExactly(auth);
        assertThat(third.next()).isNull();
    }

    @Test
    void microsecondsKeepTheirOrder() {
        UserActivityItem older = item("workflow_run", T.plusNanos(1_000), id(1).toString());
        UserActivityItem newer = item("workflow_run", T.plusNanos(2_000), id(2).toString());

        Page first = ActivityMerge.page(List.of(List.of(newer, older)), 1);

        assertThat(first.items()).containsExactly(newer);
        assertThat(first.next().at()).isEqualTo(T.plusNanos(2_000));
        assertThat(ActivityMerge.page(List.of(List.of(older)), 1).items()).containsExactly(older);
    }

    @Test
    void lastPage() {
        List<UserActivityItem> items = List.of(item("auth", T, id(3).toString()),
                item("auth", T.minusSeconds(1), id(2).toString()), item("auth", T.minusSeconds(2), id(1).toString()));

        Page page = ActivityMerge.page(List.of(items), 50);

        assertThat(page.items()).hasSize(3);
        assertThat(page.next()).isNull();
    }

    @Test
    void anExactFitHasNoNextPage() {
        List<UserActivityItem> items = List.of(item("auth", T, id(2).toString()),
                item("auth", T.minusSeconds(1), id(1).toString()));

        Page page = ActivityMerge.page(List.of(items), 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.next()).isNull();
    }

    @Test
    void noSourceHasAnItem() {
        Page page = ActivityMerge.page(List.of(List.of(), List.of()), 50);

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
    }

    @Test
    void sameInstantAndSourceOrdersByIdDescendingAsPostgresComparesUuids() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-00000000000f");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");

        Page page = ActivityMerge.page(
                List.of(List.of(item("auth", T, low.toString()), item("auth", T, high.toString()))), 10);

        assertThat(ids(page)).containsExactly(high, low);
    }

    @Test
    void fiveSourcesEachReturningLimitPlusOneMergeToExactlyLimit() {
        int limit = 3;
        List<List<UserActivityItem>> perSource = new ArrayList<>();
        int n = 1;
        for (String source : SOURCES) {
            List<UserActivityItem> rows = new ArrayList<>();
            for (int i = 0; i <= limit; i++) {
                rows.add(item(source, T.minusSeconds(i), id(n++).toString()));
            }
            perSource.add(rows);
        }

        Page page = ActivityMerge.page(perSource, limit);

        assertThat(page.items()).hasSize(limit);
        assertThat(page.items()).extracting(UserActivityItem::source)
                .containsExactly("admin_action", "auth", "support_request");
        assertThat(page.items()).allMatch(item -> item.occurredAt().equals(T));
        assertThat(page.next()).isEqualTo(new ActivityCursor(T, "support_request", page.items().get(2).id()));
    }
}
