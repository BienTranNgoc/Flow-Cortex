package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class TrashTextTest {

    @Test
    void keyOfDoUongIsDoUong() {
        assertThat(TrashText.key("  Đồ   Uống ")).isEqualTo("do uong");
    }

    @Test
    void keyIsNfcBeforeStripping() {
        String composed = "Bản Nháp Đẹp";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo(composed);
        assertThat(TrashText.key(decomposed)).isEqualTo(TrashText.key(composed)).isEqualTo("ban nhap dep");
    }

    @Test
    void keyCollapsesAnyWhitespaceRunToOneSpace() {
        assertThat(TrashText.key("a\t\n b　c")).isEqualTo("a b c");
    }

    @Test
    void displayNameIsNfcTrimmedAndCutTo200CodePoints() {
        String decomposed = Normalizer.normalize("Áo", Normalizer.Form.NFD);
        assertThat(TrashText.displayName("  " + decomposed + "  ")).contains("Áo");

        String pair = "😀";
        String input = "a".repeat(199) + pair + "b";
        assertThat(input.codePointCount(0, input.length())).isEqualTo(201);

        String name = TrashText.displayName(input).orElseThrow();
        assertThat(name.codePointCount(0, name.length())).isEqualTo(200);
        assertThat(name).isEqualTo("a".repeat(199) + pair);
    }

    @Test
    void blankOrNullNameIsUntitled() {
        assertThat(TrashText.displayName(null)).isEmpty();
        assertThat(TrashText.displayName("")).isEmpty();
        assertThat(TrashText.displayName(" \t　 ")).isEmpty();
        assertThat(TrashText.key(null)).isEmpty();
        assertThat(TrashText.key("   ")).isEmpty();
    }

    @Test
    void likePatternEscapesPercentUnderscoreBackslash() {
        assertThat(TrashText.containsPattern("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
        assertThat(TrashText.containsPattern("ban nhap")).isEqualTo("%ban nhap%");
    }
}
