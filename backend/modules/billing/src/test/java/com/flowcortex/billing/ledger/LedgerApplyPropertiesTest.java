package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LedgerApplyPropertiesTest {

    @Test
    void unsetValuesTakeTheDefaults() {
        LedgerApplyProperties properties = new LedgerApplyProperties(null, null, null);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.batchSize()).isEqualTo(100);
    }

    @Test
    void theBatchSizeBoundsAreInclusive() {
        assertThat(new LedgerApplyProperties(true, null, 1).batchSize()).isEqualTo(1);
        assertThat(new LedgerApplyProperties(true, null, 1000).batchSize()).isEqualTo(1000);
    }

    @Test
    void aBatchSizeOutsideOneToAThousandIsRefused() {
        assertThatThrownBy(() -> new LedgerApplyProperties(true, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new LedgerApplyProperties(true, null, 1001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
    }

    @Test
    void aSwitchedOffApplierStaysOff() {
        assertThat(new LedgerApplyProperties(false, null, null).enabled()).isFalse();
    }
}
