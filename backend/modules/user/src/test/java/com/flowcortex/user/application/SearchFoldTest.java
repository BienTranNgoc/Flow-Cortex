package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class SearchFoldTest {

    @Test
    void foldsAVietnameseNameToAsciiLowerCase() {
        assertThat(SearchFold.fold("Nguyễn Văn Đức")).isEqualTo("nguyen van duc");
    }

    @Test
    void foldsUppercaseVietnameseLetters() {
        assertThat(SearchFold.fold("ĐỨC")).isEqualTo("duc");
    }

    @Test
    void everyMappedLetterFoldsToItsBaseLetter() {
        assertThat(SearchFold.FROM).hasSize(134);
        assertThat(SearchFold.TO).hasSize(134);
        for (int i = 0; i < SearchFold.FROM.length(); i++) {
            String letter = String.valueOf(SearchFold.FROM.charAt(i));
            assertThat(SearchFold.fold(letter)).as(letter).isEqualTo(String.valueOf(SearchFold.TO.charAt(i)));
        }
    }

    @Test
    void decomposedInputFoldsLikeComposedInput() {
        String decomposed = Normalizer.normalize("Đúc", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("Đúc");
        assertThat(SearchFold.fold(decomposed)).isEqualTo(SearchFold.fold("Đúc")).isEqualTo("duc");
    }

    @Test
    void blankFoldsToEmpty() {
        assertThat(SearchFold.fold("")).isEmpty();
        assertThat(SearchFold.fold("   ")).isEmpty();
        assertThat(SearchFold.fold(null)).isEmpty();
    }

    @Test
    void leavesAsciiAndOtherLettersAlone() {
        assertThat(SearchFold.fold("  Duc.NV@Nodi.vn ")).isEqualTo("duc.nv@nodi.vn");
    }
}
