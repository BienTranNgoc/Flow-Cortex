package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.asset.domain.AssetQuery.GroupsQuery;
import com.flowcortex.asset.domain.AssetQuery.Kind;
import com.flowcortex.asset.domain.AssetQuery.ListQuery;
import com.flowcortex.asset.domain.AssetQuery.Sort;
import com.flowcortex.asset.domain.AssetQuery.Source;
import com.flowcortex.asset.domain.AssetQuery.TimeWindow;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AssetQueryTest {

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");

    private static Map<String, List<String>> params(String... keyValues) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.computeIfAbsent(keyValues[i], key -> new ArrayList<>()).add(keyValues[i + 1]);
        }
        return map;
    }

    private static ListQuery list(String... keyValues) {
        return ListQuery.parse(params(keyValues), NOW);
    }

    private static void assertRejects(Runnable parse, String parameter) {
        assertThatThrownBy(parse::run)
                .isInstanceOfSatisfying(AssetRequestRejected.class,
                        rejected -> assertThat(rejected.parameter()).isEqualTo(parameter));
    }

    @Test
    void defaultsAreNewestPageSize48AllTime() {
        ListQuery query = list();

        assertThat(query.sort()).isEqualTo(Sort.NEWEST);
        assertThat(query.pageSize()).isEqualTo(48);
        assertThat(query.filters().time()).isEqualTo(TimeWindow.ALL);
        assertThat(query.filters().kinds()).isEmpty();
        assertThat(query.filters().sources()).isEmpty();
        assertThat(query.filters().favorite()).isFalse();
        assertThat(query.filters().q()).isNull();
        assertThat(query.filters().tag()).isNull();
        assertThat(query.filters().group()).isNull();
        assertThat(query.filters().runId()).isNull();
        assertThat(query.cursor()).isNull();
        assertThat(query.mark()).isNull();
    }

    @Test
    void sortsAndTimeWindowsParseFromTheirWireValues() {
        assertThat(list("sort", "oldest").sort()).isEqualTo(Sort.OLDEST);
        assertThat(list("sort", "size_desc").sort()).isEqualTo(Sort.SIZE_DESC);
        assertThat(list("sort", "size_asc").sort()).isEqualTo(Sort.SIZE_ASC);
        assertThat(list("time", "7d").filters().time()).isEqualTo(TimeWindow.LAST_7_DAYS);
        assertThat(list("time", "7d").filters().lookback()).isEqualTo(Duration.ofDays(7));
        assertThat(list("time", "30d").filters().lookback()).isEqualTo(Duration.ofDays(30));
        assertThat(list().filters().lookback()).isNull();
    }

    @Test
    void kindAndSourceCsvDropEmptyItemsAndDuplicates() {
        ListQuery query = list("kind", "image,,video, image", "source", "upload,run,upload,");

        assertThat(query.filters().kinds()).containsExactly(Kind.VIDEO, Kind.IMAGE);
        assertThat(query.filters().sources()).containsExactly(Source.RUN, Source.UPLOAD);
        assertThat(list("kind", ",").filters().kinds()).isEmpty();
    }

    @Test
    void unknownKindSourceSortTimeOrGroupByIsRejectedNamingIt() {
        assertRejects(() -> list("kind", "image,pdf"), "kind");
        assertRejects(() -> list("source", "cloud"), "source");
        assertRejects(() -> list("sort", "random"), "sort");
        assertRejects(() -> list("time", "yesterday"), "time");
        assertRejects(() -> GroupsQuery.parse(params("groupBy", "month"), NOW), "groupBy");
    }

    @Test
    void repeatedSingleValueParameterIsRejected() {
        assertRejects(() -> list("sort", "newest", "sort", "oldest"), "sort");
        assertRejects(() -> list("kind", "image", "kind", "video"), "kind");
        assertRejects(() -> list("q", "a", "q", "b"), "q");
        assertRejects(() -> GroupsQuery.parse(params("page", "1", "page", "2"), NOW), "page");
    }

    @Test
    void blankSingleValueParameterCountsAsAbsent() {
        ListQuery query = list("sort", " ", "pageSize", "", "time", "", "cursor", " ", "mark", "");

        assertThat(query.sort()).isEqualTo(Sort.NEWEST);
        assertThat(query.pageSize()).isEqualTo(48);
        assertThat(query.cursor()).isNull();
        assertThat(query.mark()).isNull();
    }

    @Test
    void pageSizeOutside1To100IsRejected() {
        assertThat(list("pageSize", "1").pageSize()).isEqualTo(1);
        assertThat(list("pageSize", "100").pageSize()).isEqualTo(100);
        assertRejects(() -> list("pageSize", "0"), "pageSize");
        assertRejects(() -> list("pageSize", "101"), "pageSize");
        assertRejects(() -> list("pageSize", "-3"), "pageSize");
        assertRejects(() -> list("pageSize", "ten"), "pageSize");
        assertRejects(() -> list("pageSize", "99999999999"), "pageSize");
    }

    @Test
    void customTimeNeedsFromAndTo() {
        assertRejects(() -> list("time", "custom", "from", "2026-11-01"), "to");
        assertRejects(() -> list("time", "custom", "to", "2026-11-01"), "from");
        assertRejects(() -> list("time", "custom"), "from");
    }

    @Test
    void fromAfterToIsRejected() {
        assertRejects(() -> list("time", "custom", "from", "2026-11-02", "to", "2026-11-01"), "from");
        assertThat(list("time", "custom", "from", "2026-11-01", "to", "2026-11-01").filters().from()).isNotNull();
    }

    @Test
    void fromAndToAreHoChiMinhDaysWithToInclusive() {
        AssetQuery.Filters filters = list("time", "custom", "from", "2026-11-01", "to", "2026-11-01").filters();

        assertThat(filters.time()).isEqualTo(TimeWindow.CUSTOM);
        assertThat(filters.fromInstant()).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
        assertThat(filters.toExclusiveInstant()).isEqualTo(Instant.parse("2026-11-01T17:00:00Z"));
        assertThat(filters.lookback()).isNull();
    }

    @Test
    void fromOrToNotADateIsRejectedNamingIt() {
        assertRejects(() -> list("time", "custom", "from", "01/11/2026", "to", "2026-11-01"), "from");
        assertRejects(() -> list("time", "custom", "from", "2026-11-01", "to", "2026-11-01T00:00:00Z"), "to");
        assertRejects(() -> list("time", "custom", "from", "2026-02-30", "to", "2026-03-01"), "from");
        assertRejects(() -> list("time", "custom", "from", "2026-1-1", "to", "2026-11-01"), "from");
        assertRejects(() -> list("time", "all", "from", "nope"), "from");
    }

    @Test
    void fromAndToAreIgnoredOutsideTheCustomWindow() {
        AssetQuery.Filters filters = list("time", "7d", "from", "2026-11-01", "to", "2026-11-02").filters();

        assertThat(filters.from()).isNull();
        assertThat(filters.to()).isNull();
    }

    @Test
    void markIsAnIsoInstantNotAfterNow() {
        assertThat(list("mark", "2026-11-15T02:59:59.123456Z").mark()).isEqualTo(Instant.parse("2026-11-15T02:59:59.123456Z"));
        assertThat(list("mark", NOW.toString()).mark()).isEqualTo(NOW);
        assertRejects(() -> list("mark", "yesterday"), "mark");
        assertRejects(() -> list("mark", "2026-11-15"), "mark");
        assertRejects(() -> list("mark", NOW.plusSeconds(1).toString()), "mark");
        assertRejects(() -> GroupsQuery.parse(params("mark", NOW.plusSeconds(3600).toString()), NOW), "mark");
    }

    @Test
    void qIsNfcAndWhitespaceCollapsedBlankMeansNone() {
        String decomposed = Normalizer.normalize("Áo", Normalizer.Form.NFD);

        assertThat(list("q", "  " + decomposed + " \t thun  ").filters().q()).isEqualTo("Áo thun");
        assertThat(list("q", "   ").filters().q()).isNull();
        assertThat(list("q", "").filters().q()).isNull();
    }

    @Test
    void qOver200CharactersIsRejected() {
        assertThat(list("q", "a".repeat(200)).filters().q()).hasSize(200);
        assertRejects(() -> list("q", "a".repeat(201)), "q");
    }

    @Test
    void tagIsNormalisedLikeQAndCappedAtTheTagLabelLength() {
        assertThat(list("tag", "  Mùa   hè ").filters().tag()).isEqualTo("Mùa hè");
        assertThat(list("tag", " ").filters().tag()).isNull();
        assertRejects(() -> list("tag", "t".repeat(41)), "tag");
    }

    @Test
    void likePatternEscapesPercentUnderscoreAndBackslash() {
        assertThat(AssetQuery.likePattern("50%")).isEqualTo("50\\%");
        assertThat(AssetQuery.likePattern("a_b")).isEqualTo("a\\_b");
        assertThat(AssetQuery.likePattern("c:\\dir")).isEqualTo("c:\\\\dir");
        assertThat(AssetQuery.likePattern("plain")).isEqualTo("plain");
    }

    @Test
    void favoriteTrueFiltersAndFalseIsNoFilter() {
        assertThat(list("favorite", "true").filters().favorite()).isTrue();
        assertThat(list("favorite", "false").filters().favorite()).isFalse();
        assertRejects(() -> list("favorite", "yes"), "favorite");
    }

    @Test
    void runIdAndGroupAreStrictlyParsed() {
        String run = "0b9f6e2c-7d1e-4a3b-9c55-2f6a8d4e1b70";

        assertThat(list("runId", run).filters().runId()).hasToString(run);
        assertRejects(() -> list("runId", "1-1-1-1-1"), "runId");
        assertRejects(() -> list("runId", "not-a-uuid"), "runId");
        assertThat(list("group", "upload").filters().group()).isEqualTo(new AssetGroupKeys.Upload());
        assertRejects(() -> list("group", "workflow:nope"), "group");
    }

    @Test
    void cursorIsKeptRawForTheCursorDecoder() {
        assertThat(list("cursor", "abc").cursor()).isEqualTo("abc");
    }

    @Test
    void groupsDefaultsAndBounds() {
        GroupsQuery query = GroupsQuery.parse(params(), NOW);

        assertThat(query.groupBy()).isEqualTo(AssetGroupKeys.GroupBy.WORKFLOW);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(10);
        assertThat(query.itemsPerGroup()).isEqualTo(4);
        assertThat(query.sort()).isEqualTo(Sort.NEWEST);
        assertThat(query.mark()).isNull();
        assertThat(GroupsQuery.parse(params("groupBy", "day", "page", "3", "pageSize", "30", "itemsPerGroup", "12"), NOW))
                .satisfies(day -> {
                    assertThat(day.groupBy()).isEqualTo(AssetGroupKeys.GroupBy.DAY);
                    assertThat(day.page()).isEqualTo(3);
                    assertThat(day.pageSize()).isEqualTo(30);
                    assertThat(day.itemsPerGroup()).isEqualTo(12);
                });
        assertRejects(() -> GroupsQuery.parse(params("page", "0"), NOW), "page");
        assertRejects(() -> GroupsQuery.parse(params("pageSize", "0"), NOW), "pageSize");
        assertRejects(() -> GroupsQuery.parse(params("pageSize", "31"), NOW), "pageSize");
        assertRejects(() -> GroupsQuery.parse(params("itemsPerGroup", "0"), NOW), "itemsPerGroup");
        assertRejects(() -> GroupsQuery.parse(params("itemsPerGroup", "13"), NOW), "itemsPerGroup");
    }

    @Test
    void groupsShareTheListFiltersButNotTheGroupFilter() {
        GroupsQuery query = GroupsQuery.parse(
                params("kind", "image", "q", "ao", "group", "upload", "time", "30d", "sort", "size_asc"), NOW);

        assertThat(query.filters().kinds()).isEqualTo(Set.of(Kind.IMAGE));
        assertThat(query.filters().q()).isEqualTo("ao");
        assertThat(query.filters().time()).isEqualTo(TimeWindow.LAST_30_DAYS);
        assertThat(query.filters().group()).isNull();
        assertThat(query.sort()).isEqualTo(Sort.SIZE_ASC);
    }

    @Test
    void noParameterSelectsASpaceOrAUser() {
        ListQuery query = list("spaceId", "x", "userId", "y", "ownerId", "z");

        assertThat(query).isEqualTo(list());
    }
}
