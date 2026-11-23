package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.support.request.InboxQuery.CreatedBucket;
import com.flowcortex.support.request.InboxQuery.Parsed;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The inbox's query parameters: what each accepts, what it turns into, and which field error names a refusal. */
class InboxQueryTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final UUID CALLER = UUID.randomUUID();

    private static Parsed parse(String assignee, String created, String q, String sort, int page) {
        return InboxQuery.parse(null, null, assignee, created, q, sort, page, CALLER, NOW);
    }

    @Test
    void meMeansTheCallerAndUnassignedAndAnAccountIdAreKept() {
        UUID other = UUID.randomUUID();

        assertThat(parse("me", null, null, null, 0).filter().assignee())
                .isEqualTo(InboxQuery.AssigneeFilter.of(CALLER));
        assertThat(parse("unassigned", null, null, null, 0).filter().assignee())
                .isEqualTo(InboxQuery.AssigneeFilter.UNASSIGNED);
        assertThat(parse(other.toString(), null, null, null, 0).filter().assignee())
                .isEqualTo(InboxQuery.AssigneeFilter.of(other));
        assertThat(parse(null, null, null, null, 0).filter().assignee()).isNull();
    }

    @Test
    void anAssigneeThatIsNeitherAKeywordNorAnIdIsAFieldError() {
        Parsed parsed = parse("bob", null, null, null, 0);

        assertThat(parsed.filter()).isNull();
        assertThat(parsed.errors()).containsOnlyKeys("assignee");
    }

    @Test
    void theCreatedBucketsAreDisjointAndCutAtOneSevenAndThirtyDaysBeforeNow() {
        assertThat(parse(null, "today", null, null, 0).filter().created()).isEqualTo(CreatedBucket.TODAY);
        assertThat(parse(null, "7d", null, null, 0).filter().created()).isEqualTo(CreatedBucket.WEEK);
        assertThat(parse(null, "30d", null, null, 0).filter().created()).isEqualTo(CreatedBucket.MONTH);
        assertThat(parse(null, "older", null, null, 0).filter().created()).isEqualTo(CreatedBucket.OLDER);

        assertThat(CreatedBucket.TODAY.bounds(NOW))
                .isEqualTo(new InboxQuery.Bounds(NOW.minus(Duration.ofDays(1)), null));
        assertThat(CreatedBucket.WEEK.bounds(NOW))
                .isEqualTo(new InboxQuery.Bounds(NOW.minus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(1))));
        assertThat(CreatedBucket.MONTH.bounds(NOW))
                .isEqualTo(new InboxQuery.Bounds(NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(7))));
        assertThat(CreatedBucket.OLDER.bounds(NOW))
                .isEqualTo(new InboxQuery.Bounds(null, NOW.minus(Duration.ofDays(30))));
    }

    @Test
    void anUnknownBucketIsAFieldError() {
        assertThat(parse(null, "yesterday", null, null, 0).errors()).containsOnlyKeys("created");
    }

    @Test
    void theSearchTextIsTrimmedAndBlankOrOverlongTextIsAFieldError() {
        assertThat(parse(null, null, "  tải video ", null, 0).filter().search().pattern())
                .isEqualTo("%tải video%");
        assertThat(parse(null, null, "   ", null, 0).errors()).containsOnlyKeys("q");
        assertThat(parse(null, null, "", null, 0).errors()).containsOnlyKeys("q");
        assertThat(parse(null, null, "a".repeat(100), null, 0).errors()).isEmpty();
        assertThat(parse(null, null, "a".repeat(101), null, 0).errors()).containsOnlyKeys("q");
        assertThat(parse(null, null, null, null, 0).filter().search()).isNull();
    }

    @Test
    void aNumberWithOrWithoutAHashSearchesThatRequestAndTheSubject() {
        InboxQuery.Search hashed = parse(null, null, "#12", null, 0).filter().search();
        InboxQuery.Search bare = parse(null, null, "12", null, 0).filter().search();

        assertThat(hashed.number()).isEqualTo(12L);
        assertThat(hashed.pattern()).isEqualTo("%#12%");
        assertThat(bare.number()).isEqualTo(12L);
        assertThat(bare.pattern()).isEqualTo("%12%");
        assertThat(parse(null, null, "#12a", null, 0).filter().search().number()).isNull();
        assertThat(parse(null, null, "1".repeat(19), null, 0).filter().search().number()).isNull();
    }

    @Test
    void likeWildcardsAndTheEscapeCharacterInTheSearchAreEscaped() {
        assertThat(parse(null, null, "50%_a\\b", null, 0).filter().search().pattern())
                .isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    void sortDefaultsToNewestAndOnlyNewestAndOldestAreKnown() {
        assertThat(parse(null, null, null, null, 0).filter().sort()).isEqualTo(InboxQuery.Sort.NEWEST);
        assertThat(parse(null, null, null, "oldest", 0).filter().sort()).isEqualTo(InboxQuery.Sort.OLDEST);
        assertThat(parse(null, null, null, "top", 0).errors()).containsOnlyKeys("sort");
    }

    @Test
    void statusTypeAndPageAreCheckedAsBeforeAndAllErrorsAreReportedTogether() {
        Parsed parsed = InboxQuery.parse("open", "billing", "bob", "yesterday", "  ", "top", -1, CALLER, NOW);

        assertThat(parsed.errors()).containsOnlyKeys("status", "type", "assignee", "created", "q", "sort", "page");
        assertThat(InboxQuery.parse(null, null, null, null, null, null, 10_001, CALLER, NOW).errors())
                .containsOnlyKeys("page");
        InboxQuery.InboxFilter ok = InboxQuery.parse(" new ", "credit", null, null, null, null, 2, CALLER, NOW).filter();
        assertThat(ok.status()).isEqualTo("new");
        assertThat(ok.type()).isEqualTo("credit");
        assertThat(ok.page()).isEqualTo(2);
        assertThat(ok.now()).isEqualTo(NOW);
    }
}
