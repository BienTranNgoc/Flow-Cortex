package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The rule that fixes a run's trace id when it is first stored. */
class RunTraceIdsTest {

    private static final String FALLBACK = "0123456789abcdef0123456789abcdef";

    @Test
    void keepsAnActiveTraceIdLowercased() {
        assertThat(RunTraceIds.resolve("4BF92F3577B34DA6A3CE929D0E0E4736", () -> FALLBACK))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void replacesNullBlankAllZerosOrNonHexWithTheRandomId() {
        assertThat(RunTraceIds.resolve(null, () -> FALLBACK)).isEqualTo(FALLBACK);
        assertThat(RunTraceIds.resolve("  ", () -> FALLBACK)).isEqualTo(FALLBACK);
        assertThat(RunTraceIds.resolve("0".repeat(32), () -> FALLBACK)).isEqualTo(FALLBACK);
        assertThat(RunTraceIds.resolve("z".repeat(32), () -> FALLBACK)).isEqualTo(FALLBACK);
        assertThat(RunTraceIds.resolve("abc123", () -> FALLBACK)).isEqualTo(FALLBACK);
        assertThat(RunTraceIds.resolve("a".repeat(33), () -> FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void randomIdIsThirtyTwoHex() {
        String first = RunTraceIds.random();

        assertThat(first).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        assertThat(RunTraceIds.random()).isNotEqualTo(first);
    }
}
