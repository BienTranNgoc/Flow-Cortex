package com.flowcortex.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The speech-length check uses the video length the worker renders to. */
class ProductVideoDurationLimitTest {

    @Test
    void theVideoDurationMatchesTheSharedContract() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/workflow/build.gradle.kts)");
        JsonNode limits;
        try (InputStream in = Files.newInputStream(Path.of(dir, "limits.json"))) {
            limits = new ObjectMapper().readTree(in);
        }

        JsonNode duration = limits.get("productVideoDurationSeconds");
        assertThat(duration).as("productVideoDurationSeconds in contracts/limits.json").isNotNull();
        assertThat(duration.isInt()).as("productVideoDurationSeconds is an integer").isTrue();
        assertThat(duration.intValue()).isEqualTo(ProductVideoWorkflow.VIDEO_DURATION_SECONDS);
    }
}
