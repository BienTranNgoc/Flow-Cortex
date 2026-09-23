package com.flowcortex.workflow;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap so {@code @DataJpaTest} has a {@code
 * @SpringBootConfiguration} to find — {@code :modules:workflow}'s own test
 * classpath has no reachable one otherwise (the real
 * {@code FlowCortexApplication} lives in {@code :boot:app}, a DIFFERENT
 * Gradle subproject/classpath this module never depends on). Isolated to
 * this module's test source set — invisible to {@code :boot:app} — so it
 * cannot interfere with the real {@code ModularityTests}.
 */
@SpringBootApplication
class TestWorkflowBootApp {
}
