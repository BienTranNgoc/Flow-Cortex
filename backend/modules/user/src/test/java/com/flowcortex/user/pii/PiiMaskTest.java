package com.flowcortex.user.pii;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.pii.PiiMask.Kind;
import org.junit.jupiter.api.Test;

class PiiMaskTest {

    @Test
    void email() {
        assertThat(PiiMask.email("bientran@intellix.vn")).isEqualTo("b******n@intellix.vn");
        assertThat(PiiMask.email("lan.le@example.com")).isEqualTo("l****e@example.com");
    }

    @Test
    void shortLocalPart() {
        assertThat(PiiMask.email("ab@x.vn")).isEqualTo("ab@x.vn");
        assertThat(PiiMask.email("a@x.vn")).isEqualTo("a@x.vn");
        assertThat(PiiMask.email("@x.vn")).isEqualTo("@x.vn");
    }

    @Test
    void valueWithoutAtSign() {
        assertThat(PiiMask.email("bientran")).isEqualTo("b******n");
    }

    @Test
    void theDomainStartsAtTheFirstAtSign() {
        assertThat(PiiMask.email("lan@home@example.com")).isEqualTo("l*n@home@example.com");
    }

    @Test
    void vietnameseNameWithDoubleSpace() {
        assertThat(PiiMask.name("Trần  Ngọc Biên")).isEqualTo("T*** N*** B***");
    }

    @Test
    void oneWordName() {
        assertThat(PiiMask.name("Lan")).isEqualTo("L***");
    }

    @Test
    void surroundingAndNonBreakingSpacesAreNotWords() {
        assertThat(PiiMask.name("  Lê Thị \t Lan  ")).isEqualTo("L*** T*** L***");
        assertThat(PiiMask.name("   ")).isEmpty();
        assertThat(PiiMask.name("")).isEmpty();
    }

    @Test
    void labelHoldingAnEmail() {
        assertThat(PiiMask.label("lan@example.com")).isEqualTo("l*n@example.com");
    }

    @Test
    void labelWithoutAnEmailFollowsTheNameRule() {
        assertThat(PiiMask.label("Lê Thị Lan")).isEqualTo("L*** T*** L***");
    }

    @Test
    void nullStaysNull() {
        assertThat(PiiMask.email(null)).isNull();
        assertThat(PiiMask.name(null)).isNull();
        assertThat(PiiMask.label(null)).isNull();
    }

    @Test
    void codePointsNotUtf16Units() {
        assertThat(PiiMask.name("😀 smile")).isEqualTo("😀*** s***");
        assertThat(PiiMask.email("😀ab😁@x.vn")).isEqualTo("😀**😁@x.vn");
        assertThat(PiiMask.email("😀😁@x.vn")).isEqualTo("😀😁@x.vn");
    }

    @Test
    void theMaskOfTheCurrentEmailIsRecognised() {
        assertThat(PiiMask.isMaskOf("l****e@example.com", "lan.le@example.com", Kind.EMAIL)).isTrue();
    }

    @Test
    void aRealNewEmailIsNotAMask() {
        assertThat(PiiMask.isMaskOf("lan.moi@example.com", "lan.le@example.com", Kind.EMAIL)).isFalse();
    }

    @Test
    void theMaskOfAnotherAccountIsNotTheMaskOfThisOne() {
        assertThat(PiiMask.isMaskOf("m****n@example.com", "lan.le@example.com", Kind.EMAIL)).isFalse();
    }

    @Test
    void anIdentityMaskIsNotAMask() {
        assertThat(PiiMask.isMaskOf("ab@x.vn", "ab@x.vn", Kind.EMAIL)).isFalse();
        assertThat(PiiMask.isMaskOf("", "", Kind.NAME)).isFalse();
    }

    @Test
    void theMaskOfTheCurrentNameIsRecognised() {
        assertThat(PiiMask.isMaskOf("L*** T*** L***", "Lê Thị Lan", Kind.NAME)).isTrue();
        assertThat(PiiMask.isMaskOf("Lê Thị Lan", "Lê Thị Lan", Kind.NAME)).isFalse();
        assertThat(PiiMask.isMaskOf("L*** T*** L***", "Lê Thị Lan", Kind.EMAIL)).isFalse();
    }

    @Test
    void nullOnEitherSideIsNotAMask() {
        assertThat(PiiMask.isMaskOf(null, "lan.le@example.com", Kind.EMAIL)).isFalse();
        assertThat(PiiMask.isMaskOf("l****e@example.com", null, Kind.EMAIL)).isFalse();
    }
}
