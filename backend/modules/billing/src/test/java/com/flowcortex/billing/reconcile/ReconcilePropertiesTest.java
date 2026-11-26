package com.flowcortex.billing.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ReconcilePropertiesTest {

    private static final String PREFIX = "flowcortex.billing.reconcile";

    private static ReconcileProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind(PREFIX, ReconcileProperties.class).orElseGet(() -> new ReconcileProperties(null, null, null));
    }

    private static void assertRefused(String property, String value) {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + "." + property, value)))
                .hasStackTraceContaining(PREFIX + "." + property);
    }

    @Test
    void unsetValuesTakeTheDefaults() {
        ReconcileProperties properties = bind(Map.of());

        assertThat(properties.ageing().enabled()).isTrue();
        assertThat(properties.ageing().interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.ageing().batchSize()).isEqualTo(50);
        assertThat(properties.daily().enabled()).isTrue();
        assertThat(properties.daily().interval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.daily().batchSize()).isEqualTo(50);
        assertThat(properties.floor()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theBoundsAreInclusive() {
        ReconcileProperties low = bind(Map.of(PREFIX + ".ageing.interval", "PT30S", PREFIX + ".daily.interval", "PT30S",
                PREFIX + ".ageing.batch-size", "1", PREFIX + ".daily.batch-size", "1", PREFIX + ".floor", "PT30S"));
        ReconcileProperties high = bind(Map.of(PREFIX + ".ageing.interval", "PT1H", PREFIX + ".daily.interval", "PT1H",
                PREFIX + ".ageing.batch-size", "5000", PREFIX + ".daily.batch-size", "5000", PREFIX + ".floor", "PT1H"));

        assertThat(low.ageing().batchSize()).isEqualTo(1);
        assertThat(low.floor()).isEqualTo(Duration.ofSeconds(30));
        assertThat(high.daily().batchSize()).isEqualTo(5000);
        assertThat(high.ageing().interval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void aSwitchedOffPassStaysOff() {
        ReconcileProperties properties = bind(Map.of(PREFIX + ".ageing.enabled", "false"));

        assertThat(properties.ageing().enabled()).isFalse();
        assertThat(properties.daily().enabled()).isTrue();
    }

    @Test
    void aValueOutsideItsRangeRefusesStartupNamingTheProperty() {
        assertRefused("ageing.interval", "PT29S");
        assertRefused("ageing.interval", "PT61M");
        assertRefused("daily.interval", "PT29S");
        assertRefused("daily.interval", "PT61M");
        assertRefused("ageing.batch-size", "0");
        assertRefused("ageing.batch-size", "5001");
        assertRefused("daily.batch-size", "0");
        assertRefused("floor", "PT29S");
        assertRefused("floor", "PT61M");
    }
}
