package com.flowcortex.app.observability;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The anonymous management chain is safe only on a port of its own: on the API port it would
 * publish {@code /actuator/prometheus} to anyone, so startup refuses that configuration.
 */
class ManagementSecurityConfigTest {

    @Test
    void aManagementPortEqualToTheServerPortStopsStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("management.server.port", "8080");

        assertThatThrownBy(() -> new ManagementSecurityConfig(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.port");
    }

    @Test
    void anUnsetManagementPortStopsStartup() {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8080");

        assertThatThrownBy(() -> new ManagementSecurityConfig(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.port");
    }

    @Test
    void aSeparateManagementPortIsAccepted() {
        assertThatCode(() -> new ManagementSecurityConfig(new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("management.server.port", "8081")))
                .doesNotThrowAnyException();
        // A random port each, as the tests start the app.
        assertThatCode(() -> new ManagementSecurityConfig(new MockEnvironment()
                .withProperty("server.port", "0")
                .withProperty("management.server.port", "0")))
                .doesNotThrowAnyException();
    }
}
