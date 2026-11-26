package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** One {@code drive.read} run's two budgets: 240 seconds of reading and 500,000 characters of text. */
class DriveReadBudgetTest {

    @Test
    void aFileStartsUntilTheFirstTwoHundredAndFortySeconds() {
        assertThat(DriveReadBudget.allowsNextFile(Duration.ZERO)).isTrue();
        assertThat(DriveReadBudget.allowsNextFile(Duration.ofSeconds(239))).isTrue();
        assertThat(DriveReadBudget.allowsNextFile(Duration.ofSeconds(240))).isFalse();
        assertThat(DriveReadBudget.allowsNextFile(Duration.ofSeconds(241))).isFalse();
    }

    @Test
    void aFileStartsAtTheLastMillisecondBeforeTheBudget() {
        assertThat(DriveReadBudget.allowsNextFile(Duration.ofMillis(239_999))).isTrue();
    }

    @Test
    void textIsAddedWhileTheTotalIsBelowFiveHundredThousandCharacters() {
        assertThat(DriveReadBudget.allowsText(0)).isTrue();
        assertThat(DriveReadBudget.allowsText(499_999)).isTrue();
        assertThat(DriveReadBudget.allowsText(500_000)).isFalse();
        assertThat(DriveReadBudget.allowsText(550_000)).isFalse();
    }
}
