package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.media.api.AssetFiles;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The library's read routes over HTTP with spaces on and a real login: rows are seeded straight into
 * {@code space_asset} and {@code space_asset_tag} with explicit created times, so every ordering, window
 * and snapshot is decided by the data the test wrote, never by when the test ran.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class AssetLibraryEndpointsIT extends AbstractWorkflowHttpIT {

    private static final String ASSETS = "/api/v1/assets";
    private static final Pattern ASSET_TABLES = Pattern.compile("space_asset(?!_registration)");
    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    record ItemView(UUID id, String kind, String title, String source, String mimeType, long bytes,
            Instant createdAt, UUID runId, UUID workflowId, String workflowName, boolean favorite,
            List<String> tags, boolean missing, String thumbnailUrl) {
    }

    record CountsView(long all, long video, long image, long audio, long favorite) {
    }

    record MetaView(long total, CountsView counts, String nextCursor, Instant mark) {
    }

    record PageView(List<ItemView> items, MetaView meta) {
    }

    record GroupView(String key, String title, long count, long runCount, Instant latestAt, List<ItemView> items) {
    }

    record GroupMetaView(long totalGroups, Instant mark) {
    }

    record GroupPageView(List<GroupView> groups, GroupMetaView meta) {
    }

    record UsageEntryView(long count, long bytes) {
    }

    record ByKindView(UsageEntryView video, UsageEntryView image, UsageEntryView audio) {
    }

    record UsageView(UsageEntryView total, ByKindView byKind) {
    }

    record TagView(String label, long count) {
    }

    record TagsView(List<TagView> tags) {
    }

    record ProblemView(int status, String title, String detail, Map<String, String> errors) {
    }

    /** Counts every statement the application prepares, so a page's cost is read off the connection. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StatementCounting {

        @Bean
        static BeanPostProcessor countPreparedStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource ? counting(dataSource) : bean;
                }
            };
        }

        private static DataSource counting(DataSource target) {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object result = invoke(target, method, args);
                        return result instanceof Connection connection ? counting(connection) : result;
                    });
        }

        private static Connection counting(Connection target) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                        && ASSET_TABLES.matcher(sql).find()) {
                    STATEMENTS.add(sql);
                }
                return invoke(target, method, args);
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionSpaces sessionSpaces;

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private AssetFiles assetFiles;

    private final List<UUID> spacesSeeded = new ArrayList<>();

    @AfterEach
    void removeSeededRows() {
        for (UUID space : spacesSeeded) {
            jdbc.update("DELETE FROM space_asset WHERE space_id = ?", space);
        }
    }

    @Test
    void defaultListIsNewestFirst() {
        Member member = member("list-default");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        UUID ten = member.image("ten", base);
        UUID eleven = member.image("eleven", base.plus(Duration.ofHours(1)));
        UUID twelve = member.image("twelve", base.plus(Duration.ofHours(2)));

        PageView page = member.page("");

        assertThat(page.items()).extracting(ItemView::id).containsExactly(twelve, eleven, ten);
        assertThat(page.meta().nextCursor()).isNull();
        assertThat(page.meta().total()).isEqualTo(3);
        assertThat(page.items().get(0).tags()).isEmpty();
        assertThat(page.items().get(0).favorite()).isFalse();
    }

    @Test
    void anImageThumbnailIsSignedUnlessTheFileIsMissing() {
        Member member = member("list-thumbnail");
        Instant base = Instant.now().minus(Duration.ofHours(5));
        UUID image = member.image("Photo", base);
        UUID gone = member.image("Gone", base.plusSeconds(1));
        UUID video = member.asset("video", "Clip", 10, base.plusSeconds(2));
        jdbc.update("UPDATE space_asset SET missing_at = now() WHERE id = ?", gone);

        Map<UUID, ItemView> items = member.page("").items().stream()
                .collect(Collectors.toMap(ItemView::id, item -> item));

        assertThat(items.get(image).thumbnailUrl()).isNotBlank();
        assertThat(items.get(gone).thumbnailUrl()).isNull();
        assertThat(items.get(gone).missing()).isTrue();
        assertThat(items.get(video).thumbnailUrl()).isNull();
    }

    @Test
    void sizeSortBreaksTiesNewerFirst() {
        Member member = member("list-size");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        UUID olderFive = member.asset("image", "five-old", 5_000_000, base);
        UUID newerFive = member.asset("image", "five-new", 5_000_000, base.plusSeconds(60));
        UUID nine = member.asset("image", "nine", 9_000_000, base.plusSeconds(30));

        PageView page = member.page("sort=size_desc");
        List<UUID> walked = new ArrayList<>();
        PageView step = member.page("sort=size_desc&pageSize=1");
        walked.add(step.items().get(0).id());
        while (step.meta().nextCursor() != null) {
            step = member.page("sort=size_desc&pageSize=1&cursor=" + step.meta().nextCursor());
            walked.add(step.items().get(0).id());
        }

        assertThat(page.items()).extracting(ItemView::id).containsExactly(nine, newerFive, olderFive);
        assertThat(walked).containsExactly(nine, newerFive, olderFive);
        assertThat(member.page("sort=size_asc").items()).extracting(ItemView::id)
                .containsExactly(olderFive, newerFive, nine);
    }

    @Test
    void kindAndTimeFilterTogether() {
        Member member = member("list-kind-time");
        Instant now = Instant.now();
        UUID image = member.asset("image", "recent image", 1, now.minus(Duration.ofDays(3)));
        member.asset("video", "old video", 1, now.minus(Duration.ofDays(10)));
        member.asset("audio", "yesterday audio", 1, now.minus(Duration.ofDays(1)));

        PageView page = member.page("kind=image,video&time=7d");

        assertThat(page.items()).extracting(ItemView::id).containsExactly(image);
    }

    @Test
    void customRangeIsWholeHoChiMinhDays() {
        Member member = member("list-custom");
        UUID beforeMidnight = member.image("a", Instant.parse("2026-10-31T17:30:00Z"));
        UUID lastMinute = member.image("b", Instant.parse("2026-11-01T16:59:00Z"));
        member.image("c", Instant.parse("2026-11-01T17:00:00Z"));

        PageView page = member.page("time=custom&from=2026-11-01&to=2026-11-01");

        assertThat(page.items()).extracting(ItemView::id).containsExactlyInAnyOrder(beforeMidnight, lastMinute);
    }

    @Test
    void runIdReturnsInputsAndOutputs() {
        Member member = member("list-run");
        UUID run = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofHours(3));
        UUID inputOne = member.assetOfRun("image", "input one", "upload", null, null, run, base);
        UUID inputTwo = member.assetOfRun("image", "input two", "upload", null, null, run, base.plusSeconds(1));
        UUID output = member.assetOfRun("video", "output", "run", UUID.randomUUID(), "Product video", run,
                base.plusSeconds(2));
        member.assetOfRun("video", "other run", "run", UUID.randomUUID(), "Product video", UUID.randomUUID(),
                base.plusSeconds(3));

        PageView page = member.page("runId=" + run);

        assertThat(page.items()).extracting(ItemView::id).containsExactly(output, inputTwo, inputOne);
    }

    @Test
    void searchIgnoresAccentsCaseAndMatchesTags() {
        Member member = member("list-search");
        Instant base = Instant.now().minus(Duration.ofHours(4));
        UUID shirt = member.image("Áo thun cotton trắng · Product video", base);
        UUID watch = member.image("Đồng hồ · Product video", base.plusSeconds(1));
        UUID clip = member.image("Clip 1", base.plusSeconds(2));
        member.tag(clip, "Mùa hè", base);

        assertThat(member.page("q=ao thun").items()).extracting(ItemView::id).containsExactly(shirt);
        assertThat(member.page("q=dong HO").items()).extracting(ItemView::id).containsExactly(watch);
        PageView byTag = member.page("q=mua he");
        assertThat(byTag.items()).extracting(ItemView::id).containsExactly(clip);
        assertThat(byTag.items().get(0).tags()).containsExactly("Mùa hè");
        assertThat(member.page("tag=MUA HE").items()).extracting(ItemView::id).containsExactly(clip);
    }

    @Test
    void percentInQIsLiteral() {
        Member member = member("list-percent");
        Instant base = Instant.now().minus(Duration.ofHours(4));
        UUID sale = member.image("Sale 50% · Banner", base);
        member.image("Sale 500 · Banner", base.plusSeconds(1));
        member.image("Sale 5_0 · Banner", base.plusSeconds(2));

        assertThat(member.page("q=50%25").items()).extracting(ItemView::id).containsExactly(sale);
        assertThat(member.page("q=5%5C0").items()).isEmpty();
    }

    @Test
    void walkKeepsItsMarkWhileAssetsArrive() {
        Member member = member("list-walk");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            all.add(member.image("asset " + i, base.plusSeconds(i)));
        }

        PageView first = member.page("");
        for (int i = 0; i < 3; i++) {
            member.image("arrived " + i, first.meta().mark().plusMillis(5 + i));
        }
        PageView second = member.page("cursor=" + first.meta().nextCursor());

        assertThat(first.items()).hasSize(48);
        assertThat(second.items()).hasSize(12);
        assertThat(second.meta().nextCursor()).isNull();
        assertThat(second.meta().total()).isEqualTo(60);
        assertThat(first.meta().total()).isEqualTo(60);
        assertThat(second.meta().mark()).isEqualTo(first.meta().mark());
        List<UUID> walked = new ArrayList<>(first.items().stream().map(ItemView::id).toList());
        walked.addAll(second.items().stream().map(ItemView::id).toList());
        assertThat(walked).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        assertThat(member.page("").meta().total()).isEqualTo(63);
    }

    @Test
    void firstPageAtAMarkLeavesNewerAssetsOut() {
        Member member = member("list-mark");
        Instant base = Instant.now().minus(Duration.ofHours(2));
        UUID older = member.image("older", base);
        member.image("newest", base.plusSeconds(60));
        Instant mark = base.plusSeconds(30);

        PageView page = member.page("mark=" + mark);

        assertThat(page.items()).extracting(ItemView::id).containsExactly(older);
        assertThat(page.meta().mark()).isEqualTo(mark);
    }

    @Test
    void countsIgnoreKindFilter() {
        Member member = member("list-counts");
        Instant base = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i < 5; i++) {
            member.asset("image", "image " + i, 1, base.plusSeconds(i));
        }
        member.asset("video", "video 1", 1, base.plusSeconds(10));
        UUID favoriteVideo = member.asset("video", "video 2", 1, base.plusSeconds(11));
        jdbc.update("UPDATE space_asset SET favorite = true WHERE id = ?", favoriteVideo);

        MetaView meta = member.page("kind=image").meta();

        assertThat(meta.total()).isEqualTo(5);
        assertThat(meta.counts()).isEqualTo(new CountsView(7, 2, 5, 0, 1));
    }

    @Test
    void favoriteFilterReturnsOnlyFavorites() {
        Member member = member("list-favorite");
        Instant base = Instant.now().minus(Duration.ofHours(2));
        member.image("plain", base);
        UUID starred = member.image("starred", base.plusSeconds(1));
        jdbc.update("UPDATE space_asset SET favorite = true WHERE id = ?", starred);

        PageView page = member.page("favorite=true");

        assertThat(page.items()).extracting(ItemView::id).containsExactly(starred);
        assertThat(page.items().get(0).favorite()).isTrue();
    }

    @Test
    void cursorWithAnotherSortIs400() {
        Member member = member("cursor-sort");
        Instant base = Instant.now().minus(Duration.ofHours(2));
        member.image("one", base);
        member.image("two", base.plusSeconds(1));
        PageView first = member.page("pageSize=1");

        ResponseEntity<ProblemView> otherSort = member.rawGet("pageSize=1&sort=size_desc&cursor="
                + first.meta().nextCursor());
        ResponseEntity<ProblemView> tampered = member.rawGet("cursor=abc");
        ResponseEntity<ProblemView> otherMark = member.rawGet("pageSize=1&mark=" + base + "&cursor="
                + first.meta().nextCursor());

        for (ResponseEntity<ProblemView> refused : List.of(otherSort, tampered, otherMark)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().errors()).containsOnlyKeys("cursor");
        }
    }

    @Test
    void unknownKindIs400NamingKindInTheRequestLanguage() {
        Member member = member("list-invalid");

        ResponseEntity<ProblemView> english = member.rawGet("kind=image,pdf");
        ResponseEntity<ProblemView> vietnamese = member.rawGet("kind=image,pdf", "vi");

        assertThat(english.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(english.getBody().errors()).containsOnlyKeys("kind");
        assertThat(english.getBody().errors().get("kind")).contains("kind");
        assertThat(vietnamese.getBody().errors().get("kind")).contains("kind").contains("không hợp lệ");
    }

    @Test
    void markInTheFutureIs400NamingMark() {
        Member member = member("list-future");

        ResponseEntity<ProblemView> refused = member.rawGet("mark=" + Instant.now().plus(Duration.ofHours(1)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsOnlyKeys("mark");
    }

    @Test
    void anotherMembersAssetIs404LikeAnUnknownOne() {
        Member owner = member("one-owner");
        Member other = member("one-other");
        UUID ownersAsset = owner.image("private", Instant.now().minus(Duration.ofHours(1)));

        ResponseEntity<ProblemView> foreign = other.rawGetOne(ownersAsset.toString());
        ResponseEntity<ProblemView> unknown = other.rawGetOne(UUID.randomUUID().toString());
        ResponseEntity<ProblemView> malformed = other.rawGetOne("not-a-uuid");

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().detail()).isEqualTo(unknown.getBody().detail())
                .isEqualTo(malformed.getBody().detail());
        assertThat(foreign.getBody().title()).isEqualTo(unknown.getBody().title());
        ItemView own = owner.get("/" + ownersAsset, ItemView.class);
        assertThat(own.id()).isEqualTo(ownersAsset);
        assertThat(own.title()).isEqualTo("private");
    }

    @Test
    void noTokenIs401() {
        for (String path : List.of("", "/groups", "/usage", "/tags", "/" + UUID.randomUUID())) {
            ResponseEntity<String> response = restTemplate.exchange(uri(path, ""), HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()), String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void aPageOfOneHundredIsThreeStatements() {
        Member member = member("list-cost");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        for (int i = 0; i < 100; i++) {
            UUID id = member.image("asset " + i, base.plusSeconds(i));
            member.tag(id, "first " + i, base);
            member.tag(id, "second " + i, base.plusSeconds(1));
        }
        STATEMENTS.clear();

        PageView page = member.page("pageSize=100");

        assertThat(page.items()).hasSize(100);
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.tags()).hasSize(2);
            assertThat(item.thumbnailUrl()).isNotBlank();
        });
        assertThat(List.copyOf(STATEMENTS)).hasSize(3);
        verify(assetFiles, never()).probe(any(), any());
    }

    @Test
    void groupsByWorkflowWithUploadsApartAndOrderedByNewest() {
        Member member = member("groups-workflow");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        UUID productVideo = UUID.randomUUID();
        UUID dub = UUID.randomUUID();
        UUID runOne = UUID.randomUUID();
        UUID runTwo = UUID.randomUUID();
        member.assetOfRun("image", "input a", "upload", null, null, runOne, base);
        member.assetOfRun("image", "input b", "upload", null, null, runOne, base.plusSeconds(1));
        member.assetOfRun("video", "pv 1", "run", productVideo, "Product video", runOne, base.plusSeconds(10));
        member.assetOfRun("video", "pv 2", "run", productVideo, "Product video", runOne, base.plusSeconds(11));
        member.assetOfRun("video", "pv 3", "run", productVideo, "Product video", runTwo, base.plusSeconds(20));
        member.assetOfRun("video", "pv 4", "run", productVideo, "Product video", runTwo, base.plusSeconds(21));
        member.assetOfRun("video", "dub", "run", dub, "Dub", UUID.randomUUID(), base.plusSeconds(30));

        GroupPageView page = member.groups("");

        assertThat(page.groups()).extracting(GroupView::key)
                .containsExactly("workflow:" + dub, "workflow:" + productVideo, "upload");
        assertThat(page.groups()).extracting(GroupView::title).containsExactly("Dub", "Product video", null);
        assertThat(page.groups()).extracting(GroupView::count).containsExactly(1L, 4L, 2L);
        assertThat(page.groups()).extracting(GroupView::runCount).containsExactly(1L, 2L, 1L);
        assertThat(page.groups().get(1).items()).extracting(ItemView::title)
                .containsExactly("pv 4", "pv 3", "pv 2", "pv 1");
        assertThat(page.meta().totalGroups()).isEqualTo(3);
    }

    @Test
    void groupsByDayUseHoChiMinhDays() {
        Member member = member("groups-day");
        member.image("late", Instant.parse("2026-11-14T17:30:00Z"));
        member.image("early", Instant.parse("2026-11-14T16:30:00Z"));

        GroupPageView page = member.groups("groupBy=day");

        assertThat(page.groups()).extracting(GroupView::key).containsExactly("day:2026-11-15", "day:2026-11-14");
        assertThat(page.groups()).extracting(GroupView::title).containsExactly("2026-11-15", "2026-11-14");
    }

    @Test
    void groupsPageTwoWithMarkExcludesNewAssets() {
        Member member = member("groups-pages");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID workflow = UUID.randomUUID();
            keys.add("workflow:" + workflow);
            member.assetOfRun("video", "group " + i, "run", workflow, "Workflow " + i, UUID.randomUUID(),
                    base.plusSeconds(i));
        }
        List<String> newestFirst = new ArrayList<>(keys);
        Collections.reverse(newestFirst);

        GroupPageView first = member.groups("pageSize=10");
        UUID thirteenth = UUID.randomUUID();
        member.assetOfRun("video", "arrived", "run", thirteenth, "Arrived", UUID.randomUUID(),
                first.meta().mark().plusMillis(5));
        GroupPageView second = member.groups("pageSize=10&page=2&mark=" + first.meta().mark());

        assertThat(first.groups()).extracting(GroupView::key).containsExactlyElementsOf(newestFirst.subList(0, 10));
        assertThat(second.groups()).extracting(GroupView::key)
                .containsExactlyElementsOf(newestFirst.subList(10, 12));
        assertThat(first.meta().totalGroups()).isEqualTo(12);
        assertThat(second.meta().totalGroups()).isEqualTo(12);
        assertThat(second.meta().mark()).isEqualTo(first.meta().mark());
    }

    @Test
    void groupDrillDownTotalEqualsGroupCount() {
        Member member = member("groups-drill");
        Instant base = Instant.now().minus(Duration.ofDays(1));
        UUID productVideo = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            member.assetOfRun("video", "pv " + i, "run", productVideo, "Product video", UUID.randomUUID(),
                    base.plusSeconds(i));
        }
        member.image("upload", base);

        GroupPageView groups = member.groups("");
        GroupView group = groups.groups().stream().filter(g -> g.key().equals("workflow:" + productVideo))
                .findFirst().orElseThrow();
        member.assetOfRun("video", "pv arrived", "run", productVideo, "Product video", UUID.randomUUID(),
                groups.meta().mark().plusMillis(5));
        PageView drill = member.page("group=workflow:" + productVideo + "&mark=" + groups.meta().mark());

        assertThat(group.count()).isEqualTo(4);
        assertThat(drill.meta().total()).isEqualTo(group.count());
        assertThat(drill.items()).extracting(ItemView::id)
                .containsExactlyInAnyOrderElementsOf(group.items().stream().map(ItemView::id).toList());
        assertThat(member.page("group=upload").items()).extracting(ItemView::title).containsExactly("upload");
    }

    @Test
    void usageCountsMissingAssets() {
        Member member = member("usage");
        Instant base = Instant.now().minus(Duration.ofHours(2));
        member.asset("image", "one", 1_000_000, base);
        member.asset("image", "two", 2_000_000, base.plusSeconds(1));
        UUID video = member.asset("video", "three", 10_000_000, base.plusSeconds(2));
        jdbc.update("UPDATE space_asset SET missing_at = now() WHERE id = ?", video);

        UsageView usage = member.get("/usage", UsageView.class);

        assertThat(usage.byKind().image()).isEqualTo(new UsageEntryView(2, 3_000_000));
        assertThat(usage.byKind().video()).isEqualTo(new UsageEntryView(1, 10_000_000));
        assertThat(usage.byKind().audio()).isEqualTo(new UsageEntryView(0, 0));
        assertThat(usage.total()).isEqualTo(new UsageEntryView(3, 13_000_000));
    }

    @Test
    void tagsKeepFirstLabelPerFoldedKey() {
        Member member = member("tags");
        Instant base = Instant.now().minus(Duration.ofHours(2));
        UUID first = member.image("a", base);
        UUID second = member.image("b", base.plusSeconds(1));
        UUID third = member.image("c", base.plusSeconds(2));
        member.tag(first, "Mùa hè", base);
        member.tag(second, "mua he", base.plusSeconds(10));
        member.tag(third, "mua he", base.plusSeconds(11));
        member.tag(third, "Solo", base.plusSeconds(12));

        TagsView tags = member.get("/tags", TagsView.class);

        assertThat(tags.tags()).containsExactly(new TagView("Mùa hè", 3), new TagView("Solo", 1));
    }

    private Member member(String prefix) {
        UserView user = newUser(prefix);
        UUID space = sessionSpaces.sessionSpace(user.id()).spaceId();
        spacesSeeded.add(space);
        return new Member(user.id(), space, loginAs(user));
    }

    private URI uri(String path, String query) {
        return URI.create("http://localhost:" + port + ASSETS + path + (query.isEmpty() ? "" : "?" + encode(query)));
    }

    /** Encodes each value of an {@code a=b&c=d} query, leaving a value that already carries a {@code %XX} as given. */
    private static String encode(String query) {
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String value = pair.substring(eq + 1);
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(pair, 0, eq + 1).append(value.contains("%")
                    ? value : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    /** One signed-in account with its space; seeding writes straight to the tables. */
    private final class Member {

        private final UUID userId;
        private final UUID spaceId;
        private final String token;

        Member(UUID userId, UUID spaceId, String token) {
            this.userId = userId;
            this.spaceId = spaceId;
            this.token = token;
        }

        UUID image(String title, Instant createdAt) {
            return asset("image", title, 1_000, createdAt);
        }

        UUID asset(String kind, String title, long bytes, Instant createdAt) {
            return assetOfRun(kind, title, "upload", null, null, UUID.randomUUID(), createdAt, bytes);
        }

        UUID assetOfRun(String kind, String title, String source, UUID workflowId, String workflowName, UUID runId,
                Instant createdAt) {
            return assetOfRun(kind, title, source, workflowId, workflowName, runId, createdAt, 1_000);
        }

        private UUID assetOfRun(String kind, String title, String source, UUID workflowId, String workflowName,
                UUID runId, Instant createdAt, long bytes) {
            UUID id = UUID.randomUUID();
            String mime = switch (kind) {
                case "video" -> "video/mp4";
                case "audio" -> "audio/mpeg";
                default -> "image/png";
            };
            jdbc.update("INSERT INTO space_asset (id, space_id, owner_id, media_asset_id, source, kind, title, "
                    + "title_base, title_version, bucket, object_key, mime_type, bytes, run_id, run_step_id, "
                    + "workflow_id, workflow_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, "
                    + "?, ?, ?)", id, spaceId, userId, UUID.randomUUID(), source, kind, title, title, MEDIA_BUCKET,
                    "it/" + id, mime, bytes, runId, source.equals("run") ? UUID.randomUUID() : null, workflowId,
                    workflowName, Timestamp.from(createdAt));
            return id;
        }

        void tag(UUID assetId, String label, Instant createdAt) {
            jdbc.update("INSERT INTO space_asset_tag (asset_id, space_id, label, created_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (asset_id, tag_key) DO NOTHING", assetId, spaceId, label,
                    Timestamp.from(createdAt));
        }

        PageView page(String query) {
            return get("", query, PageView.class);
        }

        GroupPageView groups(String query) {
            return get("/groups", query, GroupPageView.class);
        }

        <T> T get(String path, Class<T> type) {
            return get(path, "", type);
        }

        private <T> T get(String path, String query, Class<T> type) {
            ResponseEntity<T> response = exchange(path, query, null, type);
            assertThat(response.getStatusCode()).as(path + "?" + query).isEqualTo(HttpStatus.OK);
            return response.getBody();
        }

        ResponseEntity<ProblemView> rawGet(String query) {
            return exchange("", query, null, ProblemView.class);
        }

        ResponseEntity<ProblemView> rawGet(String query, String language) {
            return exchange("", query, language, ProblemView.class);
        }

        ResponseEntity<ProblemView> rawGetOne(String id) {
            return exchange("/" + id, "", null, ProblemView.class);
        }

        private <T> ResponseEntity<T> exchange(String path, String query, String language, Class<T> type) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            if (language != null) {
                headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
            }
            return restTemplate.exchange(uri(path, query), HttpMethod.GET, new HttpEntity<>(headers), type);
        }
    }
}
