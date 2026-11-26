package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

/** The one text key behind search, tags, categories and label grouping, and the label form stored beside it. */
class EntityTextTest {

    @Test
    void keyOfDoUongIsDoUong() {
        assertThat(EntityText.key("  Đồ   Uống ")).isEqualTo("do uong");
    }

    @Test
    void keyIsNfcBeforeStripping() {
        String decomposed = Normalizer.normalize("Đồ Uống", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo("Đồ Uống");
        assertThat(EntityText.key(decomposed)).isEqualTo(EntityText.key("Đồ Uống")).isEqualTo("do uong");
    }

    @Test
    void blankKeyIsEmpty() {
        assertThat(EntityText.key("   ")).isEmpty();
        assertThat(EntityText.key("")).isEmpty();
        assertThat(EntityText.key(null)).isEmpty();
    }

    @Test
    void hangulKeyMayBeLongerThanItsLabel() {
        String label = "한".repeat(40);

        assertThat(EntityText.label(label)).hasSize(40);
        assertThat(EntityText.key(label).length()).isGreaterThan(40);
    }

    @Test
    void normalizedLabelIsNfcTrimmedCollapsed() {
        assertThat(EntityText.label(" Mùa  Hè ")).isEqualTo("Mùa Hè");
        assertThat(EntityText.label(Normalizer.normalize("Mùa Hè", Normalizer.Form.NFD))).isEqualTo("Mùa Hè");
        assertThat(EntityText.label(null)).isEmpty();
    }
}
