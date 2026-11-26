package com.flowcortex.trend.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrendPurgePropertiesTest {

    @Test
    void zeroGraceIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new TrendPurgeProperties(Duration.ofMinutes(15), Duration.ZERO, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trend.purge.grace");
    }

    @Test
    void zeroBatchSizeIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new TrendPurgeProperties(Duration.ofMinutes(15), Duration.ofDays(8), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trend.purge.batch-size");
    }

    @Test
    void negativeIntervalIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new TrendPurgeProperties(Duration.ofMinutes(-1), Duration.ofDays(8), 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trend.purge.interval");
    }

    @Test
    void positiveValuesAreAccepted() {
        TrendPurgeProperties properties = new TrendPurgeProperties(Duration.ofMinutes(15), Duration.ofDays(8), 20);

        assertThat(properties.interval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.grace()).isEqualTo(Duration.ofDays(8));
        assertThat(properties.batchSize()).isEqualTo(20);
    }
}
