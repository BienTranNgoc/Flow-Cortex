package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.onboarding.domain.TranslationEdit.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranslationEditTest {

    private static final StoredSample SAMPLE = new StoredSample(UUID.randomUUID(), "xin chao", "vi");

    @Test
    void noSampleIsNotSet() {
        assertThat(TranslationEdit.check(null, "en", "x", "hello")).isEqualTo(Outcome.NOT_SET);
    }

    @Test
    void otherHashIsStale() {
        assertThat(TranslationEdit.check(SAMPLE, "en", "b".repeat(64), "hello")).isEqualTo(Outcome.STALE);
    }

    @Test
    void sourceLocaleIsNotEditable() {
        assertThat(TranslationEdit.check(SAMPLE, "vi", SAMPLE.sourceHash(), "hello"))
                .isEqualTo(Outcome.LOCALE_NOT_EDITABLE);
    }

    @Test
    void blankInputClears() {
        assertThat(TranslationEdit.check(SAMPLE, "en", SAMPLE.sourceHash(), "   ")).isEqualTo(Outcome.CLEAR);
        assertThat(TranslationEdit.check(SAMPLE, "en", SAMPLE.sourceHash(), "")).isEqualTo(Outcome.CLEAR);
    }

    @Test
    void textInputSaves() {
        assertThat(TranslationEdit.check(SAMPLE, "en", SAMPLE.sourceHash(), "hello")).isEqualTo(Outcome.SAVE);
    }

    @Test
    void staleWinsOverLocale() {
        assertThat(TranslationEdit.check(SAMPLE, "vi", "b".repeat(64), "hello")).isEqualTo(Outcome.STALE);
    }
}
