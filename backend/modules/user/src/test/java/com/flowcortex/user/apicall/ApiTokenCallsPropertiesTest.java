package com.flowcortex.user.apicall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ApiTokenCallsPropertiesTest {

    private static ApiTokenCallsProperties properties(int writeLimit, int createLimit, Duration retention,
            Duration window, Duration staleAfter, DataSize maxRequest, DataSize maxResponse) {
        return new ApiTokenCallsProperties(writeLimit, createLimit, retention, window, staleAfter, maxRequest,
                maxResponse);
    }

    private static ApiTokenCallsProperties withLimits(int writeLimit, int createLimit) {
        return properties(writeLimit, createLimit, Duration.ofDays(90), Duration.ofHours(24), Duration.ofMinutes(5),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(256));
    }

    @Test
    void aNegativeTotalLimitFailsNamingTheSetting() {
        assertThatThrownBy(() -> withLimits(-1, 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.api-token-calls.daily-write-limit");
    }

    @Test
    void aNegativeCreateLimitFailsNamingTheSetting() {
        assertThatThrownBy(() -> withLimits(0, -5)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.auth.api-token-calls.daily-workflow-create-limit");
    }

    @Test
    void zeroLimitsMeanOff() {
        assertThatCode(() -> withLimits(0, 0)).doesNotThrowAnyException();
    }

    @Test
    void aNonPositiveDurationFailsNamingTheSetting() {
        assertThatThrownBy(() -> properties(0, 0, Duration.ZERO, Duration.ofHours(24), Duration.ofMinutes(5),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(256)))
                .hasMessageContaining("flowcortex.auth.api-token-calls.retention");
        assertThatThrownBy(() -> properties(0, 0, Duration.ofDays(90), Duration.ofHours(-1), Duration.ofMinutes(5),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(256)))
                .hasMessageContaining("flowcortex.auth.api-token-calls.idempotency-window");
        assertThatThrownBy(() -> properties(0, 0, Duration.ofDays(90), Duration.ofHours(24), Duration.ZERO,
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(256)))
                .hasMessageContaining("flowcortex.auth.api-token-calls.idempotency-stale-after");
    }

    @Test
    void aNonPositiveSizeFailsNamingTheSetting() {
        assertThatThrownBy(() -> properties(0, 0, Duration.ofDays(90), Duration.ofHours(24), Duration.ofMinutes(5),
                DataSize.ofBytes(0), DataSize.ofKilobytes(256)))
                .hasMessageContaining("flowcortex.auth.api-token-calls.max-keyed-request-body");
        assertThatThrownBy(() -> properties(0, 0, Duration.ofDays(90), Duration.ofHours(24), Duration.ofMinutes(5),
                DataSize.ofMegabytes(1), DataSize.ofBytes(-1)))
                .hasMessageContaining("flowcortex.auth.api-token-calls.max-stored-response-body");
    }

    @Test
    void validValuesAreKept() {
        ApiTokenCallsProperties properties = withLimits(100, 10);

        assertThat(properties.dailyWriteLimit()).isEqualTo(100);
        assertThat(properties.dailyWorkflowCreateLimit()).isEqualTo(10);
        assertThat(properties.maxKeyedRequestBody().toBytes()).isEqualTo(1024 * 1024);
    }
}
