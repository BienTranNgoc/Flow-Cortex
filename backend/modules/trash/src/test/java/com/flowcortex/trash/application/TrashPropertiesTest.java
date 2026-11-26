package com.flowcortex.trash.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrashPropertiesTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void theShippedDefaultsAreValid() {
        assertThatCode(() -> properties(Duration.ofDays(30), Duration.ofDays(3), HOUR, HOUR)).doesNotThrowAnyException();
    }

    @Test
    void anOutOfRangeRetentionFailsNamingTheProperty() {
        assertThatThrownBy(() -> properties(Duration.ofDays(366), Duration.ofDays(3), HOUR, HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trash.retention");
    }

    @Test
    void aLeadAsLongAsTheRetentionFailsNamingTheProperty() {
        assertThatThrownBy(() -> properties(Duration.ofDays(30), Duration.ofDays(30), HOUR, HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trash.reminder-lead");
    }

    @Test
    void aJobIntervalThatIsNotPositiveFailsNamingTheProperty() {
        assertThatThrownBy(() -> properties(Duration.ofDays(30), Duration.ofDays(3), Duration.ZERO, HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trash.sweep-interval");
        assertThatThrownBy(() -> properties(Duration.ofDays(30), Duration.ofDays(3), HOUR, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.trash.reminder-interval");
    }

    private static TrashProperties properties(Duration retention, Duration lead, Duration sweep, Duration reminder) {
        return new TrashProperties(true, retention, lead, sweep, reminder);
    }
}
