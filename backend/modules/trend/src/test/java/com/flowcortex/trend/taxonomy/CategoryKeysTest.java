package com.flowcortex.trend.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CategoryKeysTest {

    @Test
    void foldsVietnameseAndJoinsWithHyphens() {
        assertThat(CategoryKeys.keyOf("Đồ ăn & Thức uống")).contains("do-an-thuc-uong");
    }

    @Test
    void readsStrokedDAsD() {
        assertThat(CategoryKeys.keyOf("Đà Lạt")).contains("da-lat");
        assertThat(CategoryKeys.keyOf("đường")).contains("duong");
    }

    @Test
    void noLetterOrDigitGivesEmpty() {
        assertThat(CategoryKeys.keyOf("!!!")).isEqualTo(Optional.empty());
        assertThat(CategoryKeys.keyOf("  - _ ")).isEqualTo(Optional.empty());
    }

    @Test
    void cutsOnTheLastHyphenWithinSixtyFour() {
        String text = "a".repeat(60) + "-" + "b".repeat(9);

        assertThat(CategoryKeys.keyOf(text)).contains("a".repeat(60));
    }

    @Test
    void cutsAtSixtyFourWhenTheNextCharacterIsAHyphen() {
        String text = "a".repeat(10) + "-" + "b".repeat(53) + "-" + "c".repeat(5);

        assertThat(text.charAt(64)).isEqualTo('-');
        assertThat(CategoryKeys.keyOf(text)).contains(text.substring(0, 64));
    }

    @Test
    void candidatesRunFromBaseToTwenty() {
        List<String> candidates = CategoryKeys.candidates("thu-cung");

        assertThat(candidates).hasSize(20);
        assertThat(candidates.get(0)).isEqualTo("thu-cung");
        assertThat(candidates.get(1)).isEqualTo("thu-cung-2");
        assertThat(candidates.get(19)).isEqualTo("thu-cung-20");
    }

    @Test
    void suffixedCandidateStaysWithinSixtyFour() {
        String base = "a".repeat(50) + "-" + "b".repeat(13);

        List<String> candidates = CategoryKeys.candidates(base);

        assertThat(base).hasSize(64);
        assertThat(candidates.get(0)).isEqualTo(base);
        assertThat(candidates.get(1)).isEqualTo(base.substring(0, 62) + "-2");
        assertThat(candidates.get(19)).isEqualTo(base.substring(0, 61) + "-20").hasSize(64);
    }

    @Test
    void aHyphenAtTheCutIsDroppedBeforeTheSuffix() {
        String base = "a".repeat(60) + "-" + "b".repeat(3);

        List<String> candidates = CategoryKeys.candidates(base);

        assertThat(candidates.get(19)).isEqualTo("a".repeat(60) + "-20").doesNotContain("--");
    }
}
