package com.flowcortex;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

// Needs a real Postgres now that JPA/Flyway/the driver are unconditionally on
// the classpath (design.md D7, task 2.2) — see AbstractPostgresBootTest.
@Testcontainers
@SpringBootTest
class FlowCortexApplicationTests extends AbstractPostgresBootTest {

    @Test
    void contextLoads() {
        // Fails the build if the Spring context cannot start (bad wiring, config).
    }
}
