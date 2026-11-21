package com.flowcortex.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeltaSplitterTest {

    @Test
    void aShortTextIsOnePieceAtItsOffset() {
        List<DeltaSplitter.Piece> pieces = DeltaSplitter.split(7, "hello");

        assertThat(pieces).containsExactly(new DeltaSplitter.Piece(7, "hello"));
    }

    @Test
    void aLongTextIsCutIntoPiecesOfAtMostFourKibWhoseOffsetsAreContiguous() {
        String text = "a".repeat(DeltaSplitter.MAX_BYTES * 2 + 10);

        List<DeltaSplitter.Piece> pieces = DeltaSplitter.split(100, text);

        assertThat(pieces).hasSize(3);
        assertThat(pieces).allSatisfy(p -> assertThat(p.text().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DeltaSplitter.MAX_BYTES));
        int expected = 100;
        StringBuilder rebuilt = new StringBuilder();
        for (DeltaSplitter.Piece piece : pieces) {
            assertThat(piece.offset()).isEqualTo(expected);
            expected += piece.text().length();
            rebuilt.append(piece.text());
        }
        assertThat(rebuilt).hasToString(text);
    }

    @Test
    void multiByteTextIsMeasuredInBytesAndNeverCutInsideACharacterOrSurrogatePair() {
        String text = "😀".repeat(DeltaSplitter.MAX_BYTES / 2);

        List<DeltaSplitter.Piece> pieces = DeltaSplitter.split(0, text);

        assertThat(pieces).hasSizeGreaterThan(1);
        for (DeltaSplitter.Piece piece : pieces) {
            assertThat(piece.text().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(DeltaSplitter.MAX_BYTES);
            assertThat(Character.isLowSurrogate(piece.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(piece.text().charAt(piece.text().length() - 1))).isFalse();
        }
        assertThat(String.join("", pieces.stream().map(DeltaSplitter.Piece::text).toList())).isEqualTo(text);
    }

    @Test
    void anEmptyTextYieldsNoPieces() {
        assertThat(DeltaSplitter.split(0, "")).isEmpty();
    }
}
