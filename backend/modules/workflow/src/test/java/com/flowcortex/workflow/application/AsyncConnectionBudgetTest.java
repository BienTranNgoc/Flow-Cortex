package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AsyncConnectionBudgetTest {

    @Test
    void aPoolOfExactlyFourConnectionsPerTaskIsAccepted() {
        assertThatCode(() -> new AsyncConnectionBudget(64, 16)).doesNotThrowAnyException();
    }

    @Test
    void aPoolWithHeadroomAboveTheProductIsAccepted() {
        assertThatCode(() -> new AsyncConnectionBudget(80, 16)).doesNotThrowAnyException();
    }

    @Test
    void aPoolOneBelowTheProductIsRefusedAtStartup() {
        assertThatThrownBy(() -> new AsyncConnectionBudget(63, 16)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum-pool-size is 63")
                .hasMessageContaining("pool.max-size (16)")
                .hasMessageContaining("is 64");
    }

    @Test
    void thePreviousSizingOfFortyForSixteenTasksIsRefused() {
        assertThatThrownBy(() -> new AsyncConnectionBudget(40, 16)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnboundedTaskPoolNeverFitsAPool() {
        assertThatThrownBy(() -> new AsyncConnectionBudget(1_000_000, Integer.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRequirementDoesNotOverflowForAHugeTaskPool() {
        assertThat(AsyncConnectionBudget.required(Integer.MAX_VALUE)).isEqualTo(4L * Integer.MAX_VALUE);
    }
}
