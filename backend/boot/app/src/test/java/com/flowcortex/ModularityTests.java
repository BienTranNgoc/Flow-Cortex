package com.flowcortex;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the Spring Modulith structure of the application: module
 * boundaries / allowed dependencies (design.md D2, R-001, R-002).
 *
 * <p>Deliberately does not also generate PlantUML/C4 docs (non-goal) — keep
 * only the structural verification.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(FlowCortexApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }
}
