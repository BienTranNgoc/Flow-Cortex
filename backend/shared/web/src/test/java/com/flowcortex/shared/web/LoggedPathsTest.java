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
    void redactsTheSecretSegmentOfAnAbsoluteUrl() {
        assertThat(LoggedPaths.redact("http://localhost:8080/api/v1/workflows/by-key/AbC"))
                .isEqualTo("http://localhost:8080/api/v1/workflows/by-key/[redacted]");
        assertThat(LoggedPaths.redact("https://app.example.com/api/v1/email-links/tok/x"))
                .isEqualTo("https://app.example.com/api/v1/email-links/[redacted]/x");
    }

    @Test
    void leavesOtherAbsoluteUrlsAndNonUrlTextUnchanged() {
        assertThat(LoggedPaths.redact("http://minio:9000/bucket/k")).isEqualTo("http://minio:9000/bucket/k");
        assertThat(LoggedPaths.redact("http://minio:9000")).isEqualTo("http://minio:9000");
        assertThat(LoggedPaths.redact("GET /api/v1/workflows/by-key/AbC failed"))
                .isEqualTo("GET /api/v1/workflows/by-key/AbC failed");
    }

    @Test
    void keepsANullPathNull() {
        assertThat(LoggedPaths.redact(null)).isNull();
    }
}
