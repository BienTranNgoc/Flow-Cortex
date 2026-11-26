package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.entity.domain.LabelSuggestions.LabelUse;
import com.flowcortex.entity.domain.LabelSuggestions.Suggestion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Part labels grouped by text key, as the labels route suggests them. */
class LabelSuggestionsTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private static LabelUse use(String label, int day) {
        return new LabelUse(label, EntityText.key(label), T0.plusSeconds(day * 86_400L));
    }

    @Test
    void groupsByKeyWithTheLatestSpelling() {
        List<LabelUse> uses = List.of(use("Mặt trước", 1), use("mặt trước", 3), use("Mặt sau", 2));

        List<Suggestion> result = LabelSuggestions.group(uses, null, 8);

        assertThat(result).containsExactly(new Suggestion("mặt trước", 2), new Suggestion("Mặt sau", 1));
    }

    @Test
    void mostUsedFirstThenMostRecent() {
        List<LabelUse> uses = List.of(use("Cũ", 1), use("Mới", 5), use("Hay", 2), use("Hay", 3), use("Cũ", 1));

        List<Suggestion> result = LabelSuggestions.group(uses, "", 8);

        assertThat(result).extracting(Suggestion::label).containsExactly("Hay", "Cũ", "Mới");
        assertThat(result).extracting(Suggestion::count).containsExactly(2, 2, 1);
    }

    @Test
    void equalCountsAreOrderedByRecency() {
        List<LabelUse> uses = List.of(use("Cũ", 1), use("Mới", 5));

        assertThat(LabelSuggestions.group(uses, null, 8)).extracting(Suggestion::label).containsExactly("Mới", "Cũ");
    }

    @Test
    void qMatchesAWordPrefixOfTheKey() {
        List<LabelUse> uses = List.of(use("Mặt trước", 1), use("Góc trước", 2), use("Cận cảnh", 3), use("Sau", 4));

        assertThat(LabelSuggestions.group(uses, "TRƯ", 8)).extracting(Suggestion::label)
                .containsExactly("Góc trước", "Mặt trước");
        assertThat(LabelSuggestions.group(uses, "mat", 8)).extracting(Suggestion::label).containsExactly("Mặt trước");
        assertThat(LabelSuggestions.group(uses, "ruoc", 8)).isEmpty();
        assertThat(LabelSuggestions.group(uses, "   ", 8)).hasSize(4);
    }

    @Test
    void limitIsApplied() {
        List<LabelUse> uses = List.of(use("A", 1), use("B", 2), use("C", 3));

        assertThat(LabelSuggestions.group(uses, null, 2)).extracting(Suggestion::label).containsExactly("C", "B");
        assertThat(LabelSuggestions.group(uses, null, 0)).isEmpty();
        assertThat(LabelSuggestions.group(List.of(), null, 8)).isEmpty();
    }
}
