package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.MarketplaceQuery.Browse;
import com.flowcortex.workflow.application.MarketplaceQuery.Sort;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarketplaceQueryTest {

    private static final Set<String> KNOWN = Set.of("translate", "tts", "echo", "APPROVAL_CHECKPOINT", "qa.review",
            "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10", "t11");

    @Test
    void searchProblemNoneForBlankOrValid() {
        assertThat(MarketplaceQuery.searchProblem(null)).isEmpty();
        assertThat(MarketplaceQuery.searchProblem("")).isEmpty();
        assertThat(MarketplaceQuery.searchProblem("  \t ")).isEmpty();
        assertThat(MarketplaceQuery.searchProblem("tạo video đẹp")).isEmpty();
        assertThat(MarketplaceQuery.searchProblem("a".repeat(100))).isEmpty();
        assertThat(MarketplaceQuery.searchProblem("a b c d e f g h")).isEmpty();
    }

    @Test
    void qOver100CharsIsAProblem() {
        assertThat(MarketplaceQuery.searchProblem("a".repeat(101))).isPresent();
        assertThat(MarketplaceQuery.searchProblem("  " + "a".repeat(100) + "  ")).isEmpty();
    }

    @Test
    void qOverEightTermsIsAProblem() {
        assertThat(MarketplaceQuery.searchProblem("a b c d e f g h i")).isPresent();
        assertThat(MarketplaceQuery.searchProblem("a  b\tc\nd e   f g h")).isEmpty();
    }

    @Test
    void stepTypesOverTenDistinctIsAProblem() {
        List<String> eleven = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10", "t11");
        List<String> tenWithRepeats = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10", "t1", "t2");

        assertThat(MarketplaceQuery.stepTypesProblem(eleven, KNOWN)).isPresent();
        assertThat(MarketplaceQuery.stepTypesProblem(tenWithRepeats, KNOWN)).isEmpty();
    }

    @Test
    void unknownStepTypeIsAProblem() {
        assertThat(MarketplaceQuery.stepTypesProblem(null, KNOWN)).isEmpty();
        assertThat(MarketplaceQuery.stepTypesProblem(List.of(), KNOWN)).isEmpty();
        assertThat(MarketplaceQuery.stepTypesProblem(List.of("translate", "qa.review"), KNOWN)).isEmpty();
        assertThat(MarketplaceQuery.stepTypesProblem(List.of("translate", "teleport"), KNOWN)).isPresent();
        assertThat(MarketplaceQuery.stepTypesProblem(List.of(""), KNOWN)).isPresent();
        assertThat(MarketplaceQuery.stepTypesProblem(List.of("TTS"), KNOWN)).isPresent();
    }

    @Test
    void everySortParsedAndOthersRefused() {
        assertThat(MarketplaceQuery.Sort.parse(null)).contains(Sort.TRENDING);
        assertThat(MarketplaceQuery.Sort.parse("trending")).contains(Sort.TRENDING);
        assertThat(MarketplaceQuery.Sort.parse("popular")).contains(Sort.POPULAR);
        assertThat(MarketplaceQuery.Sort.parse("newest")).contains(Sort.NEWEST);
        assertThat(MarketplaceQuery.Sort.parse("topRated")).contains(Sort.TOP_RATED);
        assertThat(MarketplaceQuery.Sort.parse("name")).contains(Sort.NAME);
        assertThat(MarketplaceQuery.Sort.parse("hot")).isEmpty();
        assertThat(MarketplaceQuery.Sort.parse("toprated")).isEmpty();
        assertThat(MarketplaceQuery.Sort.parse("")).isEmpty();
    }

    @Test
    void browseDefaults() {
        Browse browse = MarketplaceQuery.browse(null, null, null, 0, MarketplaceQuery.DEFAULT_SIZE);

        assertThat(browse.sort()).isEqualTo(Sort.TRENDING);
        assertThat(browse.page()).isZero();
        assertThat(browse.size()).isEqualTo(20);
        assertThat(browse.terms()).isEmpty();
        assertThat(browse.stepTypes()).isEmpty();
    }

    @Test
    void blankQIsNoSearch() {
        assertThat(MarketplaceQuery.browse("   ", List.of(), Sort.NAME, 2, 10).terms()).isEmpty();
    }

    @Test
    void qSplitIntoTermsAndEscaped() {
        Browse browse = MarketplaceQuery.browse("50% a_b c\\d", List.of(), Sort.POPULAR, 1, 5);

        assertThat(browse.terms()).containsExactly("50\\%", "a\\_b", "c\\\\d");
        assertThat(browse.sort()).isEqualTo(Sort.POPULAR);
        assertThat(browse.page()).isEqualTo(1);
        assertThat(browse.size()).isEqualTo(5);
    }

    @Test
    void termsAreNfcNormalized() {
        String decomposed = "Ta" + "̣" + "o";

        assertThat(MarketplaceQuery.browse(decomposed, List.of(), null, 0, 20).terms()).containsExactly("Tạo");
    }

    @Test
    void stepTypesDeduplicated() {
        Browse browse = MarketplaceQuery.browse(null, List.of("tts", "translate", "tts"), null, 0, 20);

        assertThat(browse.stepTypes()).containsExactlyInAnyOrder("tts", "translate");
    }
}
