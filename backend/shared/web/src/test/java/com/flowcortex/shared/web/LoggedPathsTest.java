package com.flowcortex.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoggedPathsTest {

    @Test
    void redactsTheSegmentAfterTheByKeyPrefix() {
        assertThat(LoggedPaths.redact("/api/v1/workflows/by-key/AbC_123-xyz"))
                .isEqualTo("/api/v1/workflows/by-key/[redacted]");
    }

    @Test
    void keepsTheRestOfThePathAfterTheRedactedSegment() {
        assertThat(LoggedPaths.redact("/api/v1/workflows/by-key/AbC/extra"))
                .isEqualTo("/api/v1/workflows/by-key/[redacted]/extra");
    }

    @Test
    void redactsTheEmailLinkToken() {
        assertThat(LoggedPaths.redact("/api/v1/email-links/tok123"))
                .isEqualTo("/api/v1/email-links/[redacted]");
    }

    @Test
    void leavesOtherPathsUnchanged() {
        assertThat(LoggedPaths.redact("/api/v1/workflows/3f2a")).isEqualTo("/api/v1/workflows/3f2a");
        assertThat(LoggedPaths.redact("/api/v1/workflows/by-key")).isEqualTo("/api/v1/workflows/by-key");
    }

    @Test
    void keepsANullPathNull() {
        assertThat(LoggedPaths.redact(null)).isNull();
    }
}
