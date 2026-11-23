package com.flowcortex.workflow.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.StepDefinition;
import com.flowcortex.workflow.application.WorkflowArtifacts;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates what the artifact writer emits, and what the importer refuses, against the committed schema. */
class WorkflowArtifactSchemaTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/workflow/build.gradle.kts)");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (InputStream in = Files.newInputStream(Path.of(dir, "workflow-artifact.schema.json"))) {
            schema = registry.getSchema(MAPPER.readTree(in));
        }
    }

    private static ObjectNode written() {
        String text = WorkflowArtifacts.write("Daily recap", List.of(
                new StepDefinition(1, null, "generate_script", "{\"profile\":\"product_ad_v1\"}", null),
                new StepDefinition(2, "Speak", "generate_speech", null, "{step:1.script}")),
                Instant.parse("2026-11-09T10:00:00Z")).toString();
        return (ObjectNode) MAPPER.readTree(text);
    }

    @Test
    void aWrittenArtifactValidates() {
        assertThat(schema.validate(written())).isEmpty();
    }

    @Test
    void anArtifactWithNoStepsValidates() {
        ObjectNode artifact = written();
        artifact.putArray("steps");

        assertThat(schema.validate(artifact)).isEmpty();
    }

    @Test
    void theSchemaRefusesAnUnknownKey() {
        ObjectNode artifact = written();
        ((ObjectNode) artifact.get("steps").get(0)).put("ownerId", "x");
        assertThat(schema.validate(artifact)).isNotEmpty();

        ObjectNode topLevel = written();
        topLevel.put("id", "x");
        assertThat(schema.validate(topLevel)).isNotEmpty();
    }

    @Test
    void theSchemaRefusesFormatVersionTwo() {
        ObjectNode artifact = written();
        artifact.put("formatVersion", 2);

        assertThat(schema.validate(artifact)).isNotEmpty();
    }

    @Test
    void theSchemaRefusesAMissingKeyAndAnArrayConfig() {
        ObjectNode missing = written();
        ((ObjectNode) missing.get("steps").get(0)).remove("inputRef");
        assertThat(schema.validate(missing)).isNotEmpty();

        ObjectNode array = written();
        JsonNode arrayConfig = MAPPER.readTree("[1]");
        ((ObjectNode) array.get("steps").get(0)).set("config", arrayConfig);
        assertThat(schema.validate(array)).isNotEmpty();
    }
}
