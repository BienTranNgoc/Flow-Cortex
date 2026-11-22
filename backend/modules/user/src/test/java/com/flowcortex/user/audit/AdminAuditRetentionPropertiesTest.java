package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminAuditRetentionPropertiesTest {

    @Test
    void aZeroOriginRetentionIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new AdminAuditRetentionProperties(Duration.ZERO, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.admin-audit.origin-retention");
    }

    @Test
    void aNegativeOriginRetentionIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new AdminAuditRetentionProperties(Duration.ofDays(-1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.admin-audit.origin-retention");
    }

    @Test
    void aNonPositiveRetentionIntervalIsRefusedNamingTheProperty() {
        assertThatThrownBy(() -> new AdminAuditRetentionProperties(Duration.ofDays(365), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.admin-audit.retention-interval");
    }

    @Test
    void positiveValuesAreAccepted() {
        AdminAuditRetentionProperties properties =
                new AdminAuditRetentionProperties(Duration.ofDays(365), Duration.ofHours(1));

        assertThat(properties.originRetention()).isEqualTo(Duration.ofDays(365));
        assertThat(properties.retentionInterval()).isEqualTo(Duration.ofHours(1));
    }
}
