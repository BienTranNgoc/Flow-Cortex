package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.application.WorkflowArtifacts.Invalid;
import com.flowcortex.workflow.application.WorkflowArtifacts.Parsed;
import com.flowcortex.workflow.application.WorkflowArtifacts.ReadResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowArtifactsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-11-09T10:00:00Z");

    private static String step(int order, String body) {
        return "{\"stepOrder\":" + order + "," + body + "}";
    }

    private static final String ECHO = "\"name\":null,\"type\":\"echo\",\"inputRef\":null,\"config\":null";

    private static String document(String name, String... steps) {
        return "{\"formatVersion\":1,\"exportedAt\":\"2026-11-09T10:00:00Z\",\"workflow\":{\"name\":" + name
                + "},\"steps\":[" + String.join(",", steps) + "]}";
    }

    private static ReadResult read(String json) {
        return WorkflowArtifacts.read(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> faults(String json) {
        ReadResult result = read(json);
        assertThat(result).isInstanceOf(Invalid.class);
        return ((Invalid) result).faults();
    }

    private static boolean isValid(String json) {
        return read(json) instanceof Parsed;
    }

    @Test
    void writeEmitsEveryKeyWithNullsAndStepsInOrder() {
        ObjectNode written = WorkflowArtifacts.write("Daily recap", List.of(
                new StepDefinition(1, null, "generate_script", "{\"profile\":\"product_ad_v1\"}", null),
                new StepDefinition(2, "Speak", "generate_speech", null, "{step:1.script}")), AT);

        assertThat(written.fieldNames()).toIterable()
                .containsExactly("formatVersion", "exportedAt", "workflow", "steps");
        assertThat(written.get("formatVersion").asInt()).isEqualTo(1);
        assertThat(written.get("exportedAt").asText()).isEqualTo("2026-11-09T10:00:00Z");
        assertThat(written.get("workflow").get("name").asText()).isEqualTo("Daily recap");
        JsonNode first = written.get("steps").get(0);
        assertThat(first.get("stepOrder").asInt()).isEqualTo(1);
        assertThat(first.get("name").isNull()).isTrue();
        assertThat(first.get("inputRef").isNull()).isTrue();
        assertThat(first.get("config").get("profile").asText()).isEqualTo("product_ad_v1");
        JsonNode second = written.get("steps").get(1);
        assertThat(second.get("config").isNull()).isTrue();
        assertThat(second.get("inputRef").asText()).isEqualTo("{step:1.script}");
    }

    @Test
    void writeCarriesNoIdOwnerStatusOrSharing() {
        String text = WorkflowArtifacts.write("Demo", List.of(new StepDefinition(1, null, "echo", null, null)), AT)
                .toString();

        assertThat(text).doesNotContain("\"id\"", "ownerId", "status", "shared", "allowFork", "forkCount");
    }

    @Test
    void aWrittenArtifactReadsBackToTheSameNameAndSteps() throws Exception {
        String config = "{\"a\":{\"b\":[1,2,{\"c\":true}]},\"count\":3,\"price\":2.50}";
        List<StepDefinition> steps = List.of(
                new StepDefinition(1, "First", "echo", config, null),
                new StepDefinition(2, null, "echo", null, "{step:1}"));

        ObjectNode written = WorkflowArtifacts.write("Tóm tắt", steps, AT);
        ReadResult result = WorkflowArtifacts.read(JSON.writeValueAsBytes(written));

        assertThat(result).isInstanceOf(Parsed.class);
        WorkflowArtifact artifact = ((Parsed) result).artifact();
        assertThat(artifact.formatVersion()).isEqualTo(1);
        assertThat(artifact.exportedAt()).isEqualTo(AT);
        assertThat(artifact.name()).isEqualTo("Tóm tắt");
        assertThat(artifact.steps()).hasSize(2);
        StepDefinition back = artifact.steps().get(0);
        assertThat(back.name()).isEqualTo("First");
        assertThat(back.type()).isEqualTo("echo");
        assertThat(WorkflowSnapshots.sameDefinition(JSON.readTree(back.config()), JSON.readTree(config))).isTrue();
        assertThat(back.config()).contains("2.50");
        assertThat(artifact.steps().get(1)).isEqualTo(steps.get(1));
    }

    @Test
    void notJsonIsInvalid() {
        assertThat(faults("not json")).containsKey("$");
        assertThat(faults("")).containsKey("$");
        assertThat(faults(document("\"a\"", step(1, ECHO)) + " trailing")).containsKey("$");
    }

    @Test
    void aRootThatIsNotAnObjectIsInvalid() {
        assertThat(faults("[1]")).containsKey("$");
    }

    @Test
    void anUnknownStepKeyIsNamedByPath() {
        assertThat(faults(document("\"a\"", step(1, ECHO + ",\"ownerId\":\"x\""))))
                .containsOnlyKeys("steps[0].ownerId");
    }

    @Test
    void aMissingKeyIsNamedByPath() {
        assertThat(faults(document("\"a\"", step(1, "\"name\":null,\"inputRef\":null,\"config\":null"))))
                .containsOnlyKeys("steps[0].type");
        assertThat(faults("{\"formatVersion\":1,\"workflow\":{\"name\":\"a\"},\"steps\":[]}"))
                .containsOnlyKeys("exportedAt");
        assertThat(faults("{\"formatVersion\":1,\"exportedAt\":\"2026-11-09T10:00:00Z\",\"workflow\":{},"
                + "\"steps\":[]}")).containsOnlyKeys("workflow.name");
    }

    @Test
    void formatVersionTwoIsInvalid() {
        assertThat(faults(document("\"a\"", step(1, ECHO)).replace("\"formatVersion\":1", "\"formatVersion\":2")))
                .containsOnlyKeys("formatVersion");
    }

    @Test
    void aStepOrderOtherThanItsPositionIsNamed() {
        assertThat(faults(document("\"a\"", step(1, ECHO), step(3, ECHO)))).containsOnlyKeys("steps[1].stepOrder");
    }

    @Test
    void aNameOf256CodePointsIsInvalidAnd255Emoji255IsValid() {
        String emoji = "😀";
        assertThat(isValid(document("\"" + emoji.repeat(255) + "\"", step(1, ECHO)))).isTrue();
        assertThat(faults(document("\"" + emoji.repeat(256) + "\"", step(1, ECHO)))).containsOnlyKeys("workflow.name");
    }

    @Test
    void aBlankNameIsInvalid() {
        assertThat(faults(document("\"   \"", step(1, ECHO)))).containsOnlyKeys("workflow.name");
        assertThat(faults(document("null", step(1, ECHO)))).containsOnlyKeys("workflow.name");
    }

    @Test
    void aTypeOver50IsInvalid() {
        String body = "\"name\":null,\"type\":\"" + "t".repeat(51) + "\",\"inputRef\":null,\"config\":null";
        assertThat(faults(document("\"a\"", step(1, body)))).containsOnlyKeys("steps[0].type");
        assertThat(isValid(document("\"a\"", step(1, body.replace("t".repeat(51), "t".repeat(50)))))).isTrue();
    }

    @Test
    void aStepNameOrInputRefOver255IsInvalid() {
        String longValue = "x".repeat(256);
        assertThat(faults(document("\"a\"", step(1, "\"name\":\"" + longValue
                + "\",\"type\":\"echo\",\"inputRef\":\"" + longValue + "\",\"config\":null"))))
                .containsOnlyKeys("steps[0].name", "steps[0].inputRef");
    }

    @Test
    void aConfigThatIsAnArrayIsInvalid() {
        assertThat(faults(document("\"a\"", step(1, "\"name\":null,\"type\":\"echo\",\"inputRef\":null,"
                + "\"config\":[1]")))).containsOnlyKeys("steps[0].config");
    }

    @Test
    void aConfigOver50000CharactersIsInvalid() {
        String body = "\"name\":null,\"type\":\"echo\",\"inputRef\":null,\"config\":{\"k\":\"%s\"}";
        String atLimit = "x".repeat(50_000 - "{\"k\":\"\"}".length());
        assertThat(isValid(document("\"a\"", step(1, body.formatted(atLimit))))).isTrue();
        assertThat(faults(document("\"a\"", step(1, body.formatted(atLimit + "x")))))
                .containsOnlyKeys("steps[0].config");
    }

    @Test
    void allFaultsAreReportedInOnePass() {
        Map<String, String> faults = faults(document("\"\"", step(1, ECHO + ",\"extra\":1"), step(5, ECHO)));

        assertThat(faults).containsOnlyKeys("workflow.name", "steps[0].extra", "steps[1].stepOrder");
    }

    @Test
    void aDuplicateKeyIsInvalidAtItsPath() {
        String duplicated = "\"name\":null,\"type\":\"echo\",\"type\":\"echo\",\"inputRef\":null,\"config\":null";

        assertThat(faults(document("\"a\"", step(1, duplicated)))).containsOnlyKeys("steps[0].type");
    }

    @Test
    void aDuplicateKeyInsideAConfigIsInvalidAtItsPath() {
        String body = "\"name\":null,\"type\":\"echo\",\"inputRef\":null,\"config\":{\"k\":1,\"k\":2}";

        assertThat(faults(document("\"a\"", step(1, body)))).containsOnlyKeys("steps[0].config.k");
    }

    @Test
    void anExportedAtThatIsNotATimestampIsInvalid() {
        assertThat(faults(document("\"a\"", step(1, ECHO)).replace("2026-11-09T10:00:00Z", "yesterday")))
                .containsOnlyKeys("exportedAt");
    }

    @Test
    void anExportedAtOutsideYears0000To9999InUtcIsInvalid() {
        for (String outside : List.of("-999999999-01-01T00:00:00+18:00", "+999999999-12-31T23:59:59-18:00",
                "+10000-01-01T00:00:00Z", "9999-12-31T23:59:59-01:00", "0000-01-01T00:00:00+01:00")) {
            assertThat(faults(document("\"a\"", step(1, ECHO)).replace("2026-11-09T10:00:00Z", outside)))
                    .as(outside).containsOnlyKeys("exportedAt").containsValue("not_a_timestamp");
        }
    }

    @Test
    void everyExportedAtTheReaderAcceptsReadsBackAfterBeingWritten() {
        for (String edge : List.of("0000-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z",
                "0000-01-01T00:00:00-05:00", "9999-12-31T23:59:59+14:00", "2026-11-09T10:00+07:00")) {
            ReadResult first = read(document("\"a\"", step(1, ECHO)).replace("2026-11-09T10:00:00Z", edge));
            assertThat(first).as(edge).isInstanceOf(Parsed.class);
            Instant exportedAt = ((Parsed) first).artifact().exportedAt();

            ReadResult second = WorkflowArtifacts.read(WorkflowArtifacts.write("a",
                    ((Parsed) first).artifact().steps(), exportedAt).toString().getBytes(StandardCharsets.UTF_8));

            assertThat(second).as(edge).isInstanceOf(Parsed.class);
            assertThat(((Parsed) second).artifact().exportedAt()).as(edge).isEqualTo(exportedAt);
        }
    }

    @Test
    void textTheDatabaseCannotStoreIsInvalid() {
        assertThat(faults(document("\"a\\u0000b\"", step(1, ECHO)))).containsOnlyKeys("workflow.name");
        assertThat(faults(document("\"\\ud800\"", step(1, ECHO)))).containsOnlyKeys("workflow.name");
        String body = "\"name\":null,\"type\":\"echo\",\"inputRef\":null,\"config\":{\"k\":[\"x\\u0000\"]}";
        assertThat(faults(document("\"a\"", step(1, body)))).containsOnlyKeys("steps[0].config");
    }

    @Test
    void noStepsIsAValidArtifact() {
        assertThat(isValid(document("\"a\""))).isTrue();
    }

    @Test
    void filenameStripsVietnameseDiacritics() {
        assertThat(WorkflowArtifacts.filename("Tóm tắt hằng ngày")).isEqualTo("tom-tat-hang-ngay");
        assertThat(WorkflowArtifacts.filename("Đơn")).isEqualTo("don");
    }

    @Test
    void anEmptySlugFallsBackToWorkflow() {
        assertThat(WorkflowArtifacts.filename("!!! ???")).isEqualTo("workflow");
        assertThat(WorkflowArtifacts.filename("")).isEqualTo("workflow");
    }

    @Test
    void aLongNameIsCutAt80CharactersWithoutATrailingDash() {
        String slug = WorkflowArtifacts.filename("ab ".repeat(60));

        assertThat(slug).hasSizeLessThanOrEqualTo(80).doesNotEndWith("-").doesNotStartWith("-");
    }
}
