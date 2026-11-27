package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuditLogExportPropertiesTest {

    private static AuditLogExportProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("flowcortex.admin-audit", AuditLogExportProperties.class);
    }

    @Test
    void invalidLimit() {
        assertThatThrownBy(() -> new AuditLogExportProperties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowcortex.admin-audit.export-max-rows");
        assertThatThrownBy(() -> new AuditLogExportProperties(-5))
                .hasMessageContaining("flowcortex.admin-audit.export-max-rows");
    }

    @Test
    void aZeroLimitStopsBindingNamingTheProperty() {
        assertThatThrownBy(() -> bind(Map.of("flowcortex.admin-audit.export-max-rows", "0")))
                .hasStackTraceContaining("flowcortex.admin-audit.export-max-rows");
    }

    @Test
    void defaultsToTenThousandRows() {
        assertThat(bind(Map.of()).exportMaxRows()).isEqualTo(10_000);
    }

    @Test
    void aConfiguredLimitIsKept() {
        assertThat(bind(Map.of("flowcortex.admin-audit.export-max-rows", "2")).exportMaxRows()).isEqualTo(2);
    }
}
