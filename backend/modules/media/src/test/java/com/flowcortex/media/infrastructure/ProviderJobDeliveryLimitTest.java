package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The broker's delivery limit on the job queues is the value the provider worker budgets against. */
class ProviderJobDeliveryLimitTest {

    @Test
    void theJobQueueDeliveryLimitMatchesTheSharedContract() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/media/build.gradle.kts)");
        JsonNode limits;
        try (InputStream in = Files.newInputStream(Path.of(dir, "limits.json"))) {
            limits = JsonMapper.builder().build().readTree(in);
        }

        JsonNode limit = limits.get("providerJobDeliveryLimit");
        assertThat(limit).as("providerJobDeliveryLimit in contracts/limits.json").isNotNull();
        assertThat(limit.isInt()).as("providerJobDeliveryLimit is an integer").isTrue();
        assertThat(limit.intValue()).isEqualTo(MediaTopologyConfig.JOB_DELIVERY_LIMIT);
    }
}
