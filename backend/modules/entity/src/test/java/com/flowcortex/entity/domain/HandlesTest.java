package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Handle generation from a name and the handle format. */
class HandlesTest {

    @Test
    void vietnameseNameGivesAsciiHandle() {
        assertThat(Handles.generate("Áo thun cotton trắng", Set.of())).isEqualTo("ao-thun-cotton-trang");
        assertThat(Handles.generate("  Đồ   Uống!! ", Set.of())).isEqualTo("do-uong");
    }

    @Test
    void takenBaseGetsMinusTwoThenMinusThree() {
        assertThat(Handles.generate("Nodi", Set.of("nodi"))).isEqualTo("nodi-2");
        assertThat(Handles.generate("Nodi", Set.of("nodi", "nodi-2"))).isEqualTo("nodi-3");
    }

    @Test
    void suffixKeepsTheHandleWithin48() {
        String base = "a".repeat(48);

        String second = Handles.generate(base, Set.of(base));

        assertThat(second).hasSize(48).isEqualTo("a".repeat(46) + "-2");
        assertThat(Handles.generate(base, Set.of(base, second))).isEqualTo("a".repeat(46) + "-3");
        assertThat(Handles.generate("b".repeat(60), Set.of())).isEqualTo("b".repeat(48));
    }

    @Test
    void nameWithoutUsableCharactersGivesThucThe() {
        assertThat(Handles.generate("★★★", Set.of())).isEqualTo("thuc-the");
        assertThat(Handles.generate("   ", Set.of())).isEqualTo("thuc-the");
        assertThat(Handles.generate(null, Set.of("thuc-the"))).isEqualTo("thuc-the-2");
    }

    @Test
    void oneCharacterBaseGetsMinusOne() {
        assertThat(Handles.generate("A", Set.of())).isEqualTo("a-1");
        assertThat(Handles.generate("A", Set.of("a-1"))).isEqualTo("a-1-2");
    }

    @Test
    void validAcceptsOnlyLowercaseDigitsHyphen2To48() {
        assertThat(Handles.valid("nodi")).isTrue();
        assertThat(Handles.valid("a-1")).isTrue();
        assertThat(Handles.valid("ab")).isTrue();
        assertThat(Handles.valid("a".repeat(48))).isTrue();
        assertThat(Handles.valid("Nodi")).isFalse();
        assertThat(Handles.valid("a")).isFalse();
        assertThat(Handles.valid("a".repeat(49))).isFalse();
        assertThat(Handles.valid("nó")).isFalse();
        assertThat(Handles.valid("no di")).isFalse();
        assertThat(Handles.valid("nodi\n")).isFalse();
        assertThat(Handles.valid(null)).isFalse();
    }
}
