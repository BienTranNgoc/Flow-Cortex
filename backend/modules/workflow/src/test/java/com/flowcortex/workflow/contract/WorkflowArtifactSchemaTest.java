package com.flowcortex.workflow.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.application.DataStoreJson;
import com.flowcortex.workflow.application.StepDefinition;
import com.flowcortex.workflow.application.WorkflowArtifacts;
import com.flowcortex.workflow.application.WorkflowDataStore;
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

    private static ObjectNode writtenWithDataStore() {
        ObjectMapper reader = DataStoreJson.mapper();
        try {
            String text = WorkflowArtifacts.write("Dub", List.of(new StepDefinition(1, null, "echo", null, null)),
                    List.of(new WorkflowDataStore.Seed("promo", "template",
                            reader.readTree("{\"data\":{\"brief\":[1,2.50,null]}}")),
                            new WorkflowDataStore.Seed("count", "json", reader.readTree("3"))),
                    Instant.parse("2026-11-09T10:00:00Z")).toString();
            return (ObjectNode) MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anArtifactWithADataStoreSectionValidatesAndOneWithoutStillDoes() {
        ObjectNode artifact = writtenWithDataStore();

        assertThat(artifact.has("dataStore")).isTrue();
        assertThat(schema.validate(artifact)).isEmpty();
        assertThat(schema.validate(written())).isEmpty();
    }

    @Test
    void theSchemaRefusesAMalformedDataStoreEntry() {
        ObjectNode extra = writtenWithDataStore();
        ((ObjectNode) extra.get("dataStore").get(0)).put("sizeBytes", 3);
        assertThat(schema.validate(extra)).isNotEmpty();

        ObjectNode missing = writtenWithDataStore();
        ((ObjectNode) missing.get("dataStore").get(0)).remove("value");
        assertThat(schema.validate(missing)).isNotEmpty();

        ObjectNode notAnArray = writtenWithDataStore();
        notAnArray.putObject("dataStore");
        assertThat(schema.validate(notAnArray)).isNotEmpty();
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
