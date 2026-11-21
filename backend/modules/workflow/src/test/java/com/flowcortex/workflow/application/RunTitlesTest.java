package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.runhistory.RunHistoryRequestException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunTitlesTest {

    @Test
    void trimsAndKeepsATitle() {
        assertThat(RunTitles.normalize("  Launch cut  ")).contains("Launch cut");
    }

    @Test
    void emptyOrBlankClears() {
        assertThat(RunTitles.normalize(null)).isEqualTo(Optional.empty());
        assertThat(RunTitles.normalize("")).isEmpty();
        assertThat(RunTitles.normalize("   ")).isEmpty();
    }

    @Test
    void rejectsMoreThan120Characters() {
        assertThat(RunTitles.normalize("a".repeat(120))).isPresent();
        assertThat(RunTitles.normalize("😀".repeat(120))).as("counted in code points").isPresent();
        assertThatThrownBy(() -> RunTitles.normalize("a".repeat(121)))
                .isInstanceOfSatisfying(RunHistoryRequestException.class,
                        e -> assertThat(e.field()).isEqualTo("title"));
        assertThatThrownBy(() -> RunTitles.normalize("😀".repeat(121))).isInstanceOf(RunHistoryRequestException.class);
    }

    @Test
    void rejectsAControlCharacter() {
        assertThatThrownBy(() -> RunTitles.normalize("two\nlines")).isInstanceOf(RunHistoryRequestException.class);
        assertThatThrownBy(() -> RunTitles.normalize("tab\tbed")).isInstanceOf(RunHistoryRequestException.class);
        assertThatThrownBy(() -> RunTitles.normalize("nul\u0000")).isInstanceOf(RunHistoryRequestException.class);
        assertThatThrownBy(() -> RunTitles.normalize("trailing\n")).isInstanceOf(RunHistoryRequestException.class);
    }
}
