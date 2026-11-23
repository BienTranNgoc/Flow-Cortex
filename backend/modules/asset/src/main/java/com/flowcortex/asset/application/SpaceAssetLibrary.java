package com.flowcortex.asset.application;

import com.flowcortex.asset.application.AssetViews.Counts;
import com.flowcortex.asset.application.AssetViews.Group;
import com.flowcortex.asset.application.AssetViews.GroupPage;
import com.flowcortex.asset.application.AssetViews.Item;
import com.flowcortex.asset.application.AssetViews.Page;
import com.flowcortex.asset.application.AssetViews.SpaceUsage;
import com.flowcortex.asset.application.AssetViews.Tag;
import com.flowcortex.asset.application.AssetViews.Usage;
import com.flowcortex.asset.domain.AssetCursor;
import com.flowcortex.asset.domain.AssetGroupKeys;
import com.flowcortex.asset.domain.AssetGroupKeys.Day;
import com.flowcortex.asset.domain.AssetGroupKeys.Upload;
import com.flowcortex.asset.domain.AssetGroupKeys.Workflow;
import com.flowcortex.asset.domain.AssetQuery.GroupsQuery;
import com.flowcortex.asset.domain.AssetQuery.ListQuery;
import com.flowcortex.asset.domain.AssetRequestRejected;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries.AssetRow;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries.GroupRow;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries.KindUsage;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What a member reads of their space's library: the list, the groups, storage use, tags in use and
 * one asset. The caller's space comes in as an argument, already authorised; request parameters come
 * in raw and are validated here. Logs carry space and asset ids and outcome codes only.
 */
@Service
public class SpaceAssetLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(SpaceAssetLibrary.class);

    private final AssetLibraryQueries queries;
    private final AssetViews views;
    private final Clock clock;

    public SpaceAssetLibrary(AssetLibraryQueries queries, AssetViews views, Clock clock) {
        this.queries = queries;
        this.views = views;
        this.clock = clock;
    }

    /**
     * One page of the list. The walk's mark is the cursor's, else the request's, else now.
     *
     * @throws AssetRequestRejected naming the first parameter that is not valid
     */
    public Page list(UUID spaceId, Map<String, List<String>> params) {
        Instant now = now();
        ListQuery query = ListQuery.parse(params, now);
        AssetCursor cursor = query.cursor() == null ? null : AssetCursor.decode(query.cursor(), query);
        Instant mark = cursor != null ? cursor.mark() : query.mark() != null ? query.mark() : now;

        AssetLibraryQueries.Counts counts = queries.counts(spaceId, query.filters(), mark);
        List<AssetRow> fetched = queries.page(spaceId, query, cursor, mark);
        boolean more = fetched.size() > query.pageSize();
        List<AssetRow> rows = more ? fetched.subList(0, query.pageSize()) : fetched;
        Map<UUID, List<String>> tags = queries.tagsOf(spaceId, rows.stream().map(AssetRow::id).toList());

        String nextCursor = null;
        if (more) {
            AssetRow last = rows.get(rows.size() - 1);
            nextCursor = AssetCursor.after(query, mark, last.createdAt(), last.id(), last.bytes()).encode();
        }
        LOG.info("asset.library.list space={} outcome=ok items={} more={}", spaceId, rows.size(), more);
        return new Page(items(rows, tags), counts.total(),
                new Counts(counts.all(), counts.video(), counts.image(), counts.audio(), counts.favorite()),
                nextCursor, mark);
    }

    /**
     * One page of groups. Page 2 and later send the first page's mark so a group does not appear twice.
     *
     * @throws AssetRequestRejected naming the first parameter that is not valid
     */
    public GroupPage groups(UUID spaceId, Map<String, List<String>> params) {
        Instant now = now();
        GroupsQuery query = GroupsQuery.parse(params, now);
        Instant mark = query.mark() != null ? query.mark() : now;

        List<GroupRow> rows = queries.groups(spaceId, query, mark);
        long totalGroups = queries.groupCount(spaceId, query, mark);
        List<UUID> ids = new ArrayList<>();
        rows.forEach(group -> group.items().forEach(item -> ids.add(item.id())));
        Map<UUID, List<String>> tags = queries.tagsOf(spaceId, ids);

        List<Group> groups = rows.stream()
                .map(row -> new Group(row.key(), title(row), row.count(), row.runCount(), row.latestAt(),
                        items(row.items(), tags)))
                .toList();
        LOG.info("asset.library.groups space={} outcome=ok groups={} totalGroups={}", spaceId, groups.size(),
                totalGroups);
        return new GroupPage(groups, totalGroups, mark);
    }

    /** Assets and bytes per kind and in total, every row of the space counted. */
    public SpaceUsage usage(UUID spaceId) {
        Map<String, KindUsage> byKind = new HashMap<>();
        queries.usage(spaceId).forEach(usage -> byKind.put(usage.kind(), usage));
        long count = 0;
        long bytes = 0;
        for (KindUsage usage : byKind.values()) {
            count += usage.count();
            bytes += usage.bytes();
        }
        LOG.info("asset.library.usage space={} outcome=ok", spaceId);
        return new SpaceUsage(new Usage(count, bytes), usageOf(byKind, "video"), usageOf(byKind, "image"),
                usageOf(byKind, "audio"));
    }

    /** The tags in use in the space, most used first. */
    public List<Tag> tags(UUID spaceId) {
        List<Tag> tags = queries.tags(spaceId).stream().map(tag -> new Tag(tag.label(), tag.count())).toList();
        LOG.info("asset.library.tags space={} outcome=ok tags={}", spaceId, tags.size());
        return tags;
    }

    /**
     * One asset of the space.
     *
     * @throws NotFoundException 404 {@code asset.notFound} for a malformed id, an unknown one and another space's
     */
    public Item one(UUID spaceId, String idText) {
        UUID id = parseId(idText);
        if (id == null) {
            LOG.info("asset.library.one space={} outcome=not_found", spaceId);
            throw notFound();
        }
        AssetRow row = queries.one(spaceId, id).orElseThrow(() -> {
            LOG.info("asset.library.one space={} asset={} outcome=not_found", spaceId, id);
            return notFound();
        });
        Item item = views.item(row, queries.tagsOf(spaceId, List.of(id)).getOrDefault(id, List.of()));
        LOG.info("asset.library.one space={} asset={} outcome=ok", spaceId, id);
        return item;
    }

    private List<Item> items(List<AssetRow> rows, Map<UUID, List<String>> tags) {
        return rows.stream().map(row -> views.item(row, tags.getOrDefault(row.id(), List.of()))).toList();
    }

    private static String title(GroupRow row) {
        return switch (AssetGroupKeys.parse(row.key())) {
            case Workflow workflow -> row.workflowName();
            case Upload upload -> null;
            case Day day -> day.day().toString();
        };
    }

    private static Usage usageOf(Map<String, KindUsage> byKind, String kind) {
        KindUsage usage = byKind.get(kind);
        return usage == null ? new Usage(0, 0) : new Usage(usage.count(), usage.bytes());
    }

    /** The id in its canonical text form, or null: the same refusal for any text that does not name an asset. */
    static UUID parseId(String text) {
        try {
            UUID parsed = UUID.fromString(text);
            // fromString accepts short forms such as 1-1-1-1-1; only the canonical text names an asset.
            return parsed.toString().equalsIgnoreCase(text) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException(new LocalizedMessage("asset.notFound"));
    }

    /** The clock's instant at the precision the database stores, so a mark equals what a row stamped at it holds. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
