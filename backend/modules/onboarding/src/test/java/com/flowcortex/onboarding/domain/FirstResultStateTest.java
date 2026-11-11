package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FirstResultStateTest {

    @Test
    void reportedWhenAReportExists() {
        assertThat(FirstResultState.of(true, true)).isEqualTo(FirstResultState.REPORTED);
        assertThat(FirstResultState.of(true, false)).isEqualTo(FirstResultState.REPORTED);
    }

    @Test
    void pendingWhenNoReportAndARunCompleted() {
        assertThat(FirstResultState.of(false, true)).isEqualTo(FirstResultState.PENDING);
        assertThat(FirstResultState.PENDING.wire()).isEqualTo("pending");
    }

    @Test
    void noneWhenNothingCompleted() {
        assertThat(FirstResultState.of(false, false)).isEqualTo(FirstResultState.NONE);
        assertThat(FirstResultState.NONE.wire()).isEqualTo("none");
    }
}
