package com.flowcortex.drive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.drive.domain.MemoryFilter.Parsed;
import com.flowcortex.drive.domain.MemoryFilter.Sql;
import com.flowcortex.drive.domain.MemoryFilter.SizeBucket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Parsing of the Memory list query into a space-scoped, parameterised SQL filter. */
class MemoryFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID SPACE = UUID.randomUUID();

    private static Parsed parse(String q, String type, String time, Instant from, Instant to, String size,
            int pageSize) {
        return MemoryFilter.parse(q, type == null ? null : List.of(type), time, from, to,
                size == null ? null : List.of(size), null, 0, pageSize, NOW);
    }

    private static Parsed time(String time, Instant from, Instant to) {
        return parse(null, null, time, from, to, null, 20);
    }

    @Test
    void bucketBoundariesAreInclusiveOf1MiBAnd50MiB() {
        assertThat(SizeBucket.of(0)).isEqualTo(SizeBucket.LT_1MB);
        assertThat(SizeBucket.of(1_048_575)).isEqualTo(SizeBucket.LT_1MB);
        assertThat(SizeBucket.of(1_048_576)).isEqualTo(SizeBucket.BETWEEN_1_AND_50MB);
        assertThat(SizeBucket.of(52_428_800)).isEqualTo(SizeBucket.BETWEEN_1_AND_50MB);
        assertThat(SizeBucket.of(52_428_801)).isEqualTo(SizeBucket.GT_50MB);
        assertThat(SizeBucket.LT_1MB.predicate()).isEqualTo("size_bytes < 1048576");
        assertThat(SizeBucket.BETWEEN_1_AND_50MB.predicate())
                .isEqualTo("size_bytes >= 1048576 AND size_bytes <= 52428800");
        assertThat(SizeBucket.GT_50MB.predicate()).isEqualTo("size_bytes > 52428800");
        assertThat(SizeBucket.fromWire("1to50mb")).contains(SizeBucket.BETWEEN_1_AND_50MB);
        assertThat(SizeBucket.fromWire("huge")).isEmpty();
    }

    @Test
    void sevenAndThirtyDaysCountBackFromNow() {
        Parsed seven = time("7d", null, null);
        Parsed thirty = time("30d", null, null);

        assertThat(seven.errors()).isEmpty();
        assertThat(seven.filter().from()).isEqualTo(NOW.minus(Duration.ofDays(7)));
        assertThat(seven.filter().to()).isNull();
        assertThat(thirty.filter().from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(seven.filter().where(SPACE).params().get("from"))
                .isEqualTo(NOW.minus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC));
        Parsed all = time("all", null, null);
        assertThat(all.filter().from()).isNull();
        assertThat(all.filter().where(SPACE).where()).doesNotContain("result_at");
        assertThat(time(null, null, null).errors()).isEmpty();

        Parsed custom = time("custom", NOW.minusSeconds(60), NOW);
        assertThat(custom.errors()).isEmpty();
        assertThat(custom.filter().where(SPACE).where()).contains("result_at >= :from").contains("result_at <= :to");
        assertThat(time("custom", null, NOW).filter().where(SPACE).where()).doesNotContain(":from");
        assertThat(time("fortnight", null, null).errors()).containsEntry("time", "drive.value.invalid");
    }

    @Test
    void customWithFromAfterToIsRefusedOnFrom() {
        Parsed parsed = time("custom", NOW, NOW.minusSeconds(1));

        assertThat(parsed.errors()).containsExactly(Map.entry("from", "drive.time.rangeInvalid"));
        assertThat(parsed.filter()).isNull();
    }

    @Test
    void customWithNeitherBoundIsRefused() {
        assertThat(time("custom", null, null).errors()).containsExactly(Map.entry("time", "drive.value.invalid"));
    }

    @Test
    void percentUnderscoreAndBackslashInQAreLiteral() {
        assertThat(MemoryFilter.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");

        Sql sql = parse("50%", null, null, null, null, null, 20).filter().where(SPACE);

        assertThat(sql.params().get("q")).isEqualTo("%50\\%%");
        assertThat(sql.where()).contains("title ILIKE :q ESCAPE '\\'");
        assertThat(parse("x".repeat(201), null, null, null, null, null, 20).errors())
                .containsEntry("q", "drive.value.invalid");
        assertThat(parse("x".repeat(200), null, null, null, null, null, 20).errors()).isEmpty();
        assertThat(parse("   ", null, null, null, null, null, 20).filter().where(SPACE).where())
                .doesNotContain("ILIKE");
    }

    @Test
    void pageSizeOutside1To100IsRefused() {
        assertThat(parse(null, null, null, null, null, null, 0).errors())
                .containsExactly(Map.entry("pageSize", "drive.value.invalid"));
        assertThat(parse(null, null, null, null, null, null, 101).errors())
                .containsExactly(Map.entry("pageSize", "drive.value.invalid"));
        assertThat(parse(null, null, null, null, null, null, 1).errors()).isEmpty();
        assertThat(parse(null, null, null, null, null, null, 100).errors()).isEmpty();
        assertThat(MemoryFilter.parse(null, null, null, null, null, null, null, -1, 20, NOW).errors())
                .containsExactly(Map.entry("page", "drive.value.invalid"));
        assertThat(MemoryFilter.parse(null, null, null, null, null, null, "random", 0, 20, NOW).errors())
                .containsExactly(Map.entry("sort", "drive.value.invalid"));
        assertThat(parse(null, "document", null, null, null, null, 20).errors())
                .containsExactly(Map.entry("type", "drive.value.invalid"));
        assertThat(parse(null, null, null, null, null, "tiny", 20).errors())
                .containsExactly(Map.entry("size", "drive.value.invalid"));
        MemoryFilter paged = MemoryFilter.parse(null, null, null, null, null, null, null, 3, 25, NOW).filter();
        assertThat(paged.offset()).isEqualTo(75);
        assertThat(paged.pageSize()).isEqualTo(25);
    }

    @Test
    void bucketCountsIgnoreOnlyTheSizeFilter() {
        MemoryFilter filter = parse("promo", "video", "7d", null, null, "gt50mb", 20).filter();

        Sql all = filter.where(SPACE);
        Sql facets = filter.whereIgnoringSize(SPACE);

        assertThat(all.where()).contains("space_id = :spaceId", "NOT hidden", "kind IN (:types)", "ILIKE :q",
                "result_at >= :from", "size_bytes > 52428800");
        assertThat(facets.where()).contains("space_id = :spaceId", "NOT hidden", "kind IN (:types)", "ILIKE :q",
                "result_at >= :from").doesNotContain("size_bytes");
        assertThat(facets.params()).containsEntry("spaceId", SPACE).containsEntry("types", List.of("video"));
        assertThat(filter.orderBy()).isEqualTo("result_at DESC, id DESC");
    }

    @Test
    void repeatedTypeAndSizeValuesMatchAnyOfThem() {
        MemoryFilter filter = MemoryFilter.parse(null, List.of("video", "image", "video"), null, null, null,
                List.of("gt50mb", "lt1mb"), null, 0, 20, NOW).filter();

        Sql all = filter.where(SPACE);

        assertThat(all.params()).containsEntry("types", List.of("image", "video"));
        assertThat(all.where()).contains("kind IN (:types)",
                "((size_bytes < 1048576) OR (size_bytes > 52428800))");
        assertThat(filter.whereIgnoringSize(SPACE).where()).doesNotContain("size_bytes");
    }

    @Test
    void anyUnknownValueAmongRepeatedOnesIsRefusedOnItsField() {
        assertThat(MemoryFilter.parse(null, List.of("image", "pdf"), null, null, null, List.of("lt1mb", "tiny"), null,
                0, 20, NOW).errors()).containsOnly(Map.entry("type", "drive.value.invalid"),
                Map.entry("size", "drive.value.invalid"));
    }

    @Test
    void everySortHasAStableTieBreak() {
        assertThat(MemoryFilter.parse(null, null, null, null, null, null, "oldest", 0, 20, NOW).filter().orderBy())
                .isEqualTo("result_at ASC, id ASC");
        assertThat(MemoryFilter.parse(null, null, null, null, null, null, "size_desc", 0, 20, NOW).filter().orderBy())
                .isEqualTo("size_bytes DESC, result_at DESC, id DESC");
        assertThat(MemoryFilter.parse(null, null, null, null, null, null, "size_asc", 0, 20, NOW).filter().orderBy())
                .isEqualTo("size_bytes ASC, result_at DESC, id DESC");
    }
}
