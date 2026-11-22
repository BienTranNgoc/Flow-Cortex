package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SampleTranslationsTest {

    private static final UUID WORKFLOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final StoredSample SAMPLE = new StoredSample(WORKFLOW, "xin chao", "vi");

    @Test
    void hashChangesWithWorkflowLocaleOrInput() {
        String base = SampleSourceHash.of(WORKFLOW, "vi", "xin chao");
        assertThat(SampleSourceHash.of(UUID.randomUUID(), "vi", "xin chao")).isNotEqualTo(base);
        assertThat(SampleSourceHash.of(WORKFLOW, "en", "xin chao")).isNotEqualTo(base);
        assertThat(SampleSourceHash.of(WORKFLOW, "vi", "xin chao!")).isNotEqualTo(base);
        assertThat(base).matches("[0-9a-f]{64}");
    }

    @Test
    void sameSampleSameHash() {
        assertThat(SampleSourceHash.of(WORKFLOW, "vi", "xin chao"))
                .isEqualTo(SampleSourceHash.of(WORKFLOW, "vi", "xin chao"));
        assertThat(SAMPLE.sourceHash()).isEqualTo(SampleSourceHash.of(WORKFLOW, "vi", "xin chao"));
    }

    @Test
    void editableLocalesExcludeTheSource() {
        assertThat(SampleTranslations.editableLocales("vi")).containsExactly("en");
        assertThat(SampleTranslations.editableLocales("en")).containsExactly("vi");
    }

    @Test
    void servedInputPrefersTheRequestLocaleTranslation() {
        var translations = List.of(new SampleTranslations.Translation(SAMPLE.sourceHash(), "en", "hello"));
        assertThat(SampleTranslations.servedInput(SAMPLE, translations, "en")).isEqualTo("hello");
    }

    @Test
    void servedInputFallsBackToTheSource() {
        assertThat(SampleTranslations.servedInput(SAMPLE, List.of(), "en")).isEqualTo("xin chao");
        assertThat(SampleTranslations.servedInput(SAMPLE, List.of(), "fr")).isEqualTo("xin chao");
    }

    @Test
    void translationUnderAnotherHashIsNotServed() {
        var translations = List.of(new SampleTranslations.Translation("a".repeat(64), "en", "old"));
        assertThat(SampleTranslations.servedInput(SAMPLE, translations, "en")).isEqualTo("xin chao");
    }
}
