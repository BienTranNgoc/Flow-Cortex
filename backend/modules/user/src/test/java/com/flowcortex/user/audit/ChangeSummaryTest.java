package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChangeSummaryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String[] NO_CAPTURE = {};
    private static final Map<String, String> NO_PARAMS = Map.of();

    private final Logger logger = (Logger) LoggerFactory.getLogger(ChangeSummary.class);
    private ListAppender<ILoggingEvent> appender;

    private record UpdateUserRequest(String email, String name, String role, String status) {}

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private JsonNode tree(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    void anUndeclaredFieldIsDropped() {
        JsonNode summary = ChangeSummary.build(
                tree("{\"enabled\": false, \"apiKey\": \"sk-123\"}"),
                "support_setting.update",
                new String[] {"enabled"},
                NO_PARAMS,
                MAPPER);

        assertThat(summary).isEqualTo(tree("{\"enabled\": false}"));
    }

    @Test
    void aDeclaredFieldTheBodyLacksGivesNull() {
        JsonNode summary = ChangeSummary.build(
                tree("{\"name\": \"Hoa\"}"), "user.update", new String[] {"role", "status"}, NO_PARAMS, MAPPER);

        assertThat(summary).isNull();
    }

    @Test
    void aDeclaredFieldThatIsNullIsAbsent() {
        UpdateUserRequest body = new UpdateUserRequest("new@example.com", null, "CREATOR", null);

        JsonNode summary =
                ChangeSummary.build(body, "user.update", new String[] {"role", "status"}, NO_PARAMS, MAPPER);

        assertThat(summary).isEqualTo(tree("{\"role\": \"CREATOR\"}"));
    }

    @Test
    void aRecordCarryingOnlyAnUndeclaredFieldGivesNull() {
        UpdateUserRequest body = new UpdateUserRequest(null, "Hoa", null, null);

        JsonNode summary =
                ChangeSummary.build(body, "user.update", new String[] {"role", "status"}, NO_PARAMS, MAPPER);

        assertThat(summary).isNull();
    }

    @Test
    void aNullDeclaredFieldInsideAnArrayElementIsDropped() {
        JsonNode summary = ChangeSummary.build(
                tree("[{\"key\": \"a\", \"value\": null}, {\"key\": null, \"value\": \"x\"}]"),
                "setting.update",
                new String[] {"key", "value"},
                NO_PARAMS,
                MAPPER);

        assertThat(summary).isEqualTo(tree("{\"items\": [{\"key\": \"a\"}, {\"value\": \"x\"}]}"));
    }

    @Test
    void aPathParameterWinsOverABodyField() {
        JsonNode summary = ChangeSummary.build(
                tree("{\"id\": \"B\"}"), "workflow.update", new String[] {"id"}, Map.of("id", "A"), MAPPER);

        assertThat(summary).isEqualTo(tree("{\"id\": \"A\"}"));
    }

    @Test
    void anArrayOfSixtyKeepsFiftyAndSaysTruncated() {
        List<Map<String, Object>> body = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            body.add(Map.of("key", "k" + i));
        }

        JsonNode summary = ChangeSummary.build(body, "setting.update", new String[] {"key"}, NO_PARAMS, MAPPER);

        assertThat(summary.get("items")).hasSize(50);
        assertThat(summary.get("items").get(49).get("key").asString()).isEqualTo("k49");
        assertThat(summary.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void anArrayOfFiftyIsNotMarkedTruncated() {
        List<Map<String, Object>> body = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            body.add(Map.of("key", "k" + i));
        }

        JsonNode summary = ChangeSummary.build(body, "setting.update", new String[] {"key"}, NO_PARAMS, MAPPER);

        assertThat(summary.get("items")).hasSize(50);
        assertThat(summary.has("truncated")).isFalse();
    }

    @Test
    void anArrayElementWithoutADeclaredFieldIsDropped() {
        JsonNode summary = ChangeSummary.build(
                tree("[{\"key\": \"a\"}, {\"other\": 1}, {\"key\": \"b\"}]"),
                "setting.update",
                new String[] {"key"},
                NO_PARAMS,
                MAPPER);

        assertThat(summary).isEqualTo(tree("{\"items\": [{\"key\": \"a\"}, {\"key\": \"b\"}]}"));
    }

    @Test
    void anArrayWithNothingDeclaredGivesNull() {
        JsonNode summary = ChangeSummary.build(
                tree("[{\"other\": 1}]"), "setting.update", new String[] {"key"}, NO_PARAMS, MAPPER);

        assertThat(summary).isNull();
    }

    @Test
    void anOversizedSummaryIsNullAndTheWarningNamesOnlyTheAction() {
        String value = "v".repeat(5000);

        JsonNode summary = ChangeSummary.build(
                tree("{\"value\": \"" + value + "\"}"), "setting.update", new String[] {"value"}, NO_PARAMS, MAPPER);

        assertThat(summary).isNull();
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("setting.update").doesNotContain("vvvv");
    }

    @Test
    void aMultiByteSummaryIsMeasuredInUtf8Bytes() {
        String value = "ệ".repeat(1500);

        JsonNode summary = ChangeSummary.build(
                tree("{\"value\": \"" + value + "\"}"), "setting.update", new String[] {"value"}, NO_PARAMS, MAPPER);

        assertThat(summary).isNull();
    }

    @Test
    void noCaptureAndNoParamsGivesNull() {
        JsonNode summary =
                ChangeSummary.build(tree("{\"name\": \"Hoa\"}"), "workflow.delete", NO_CAPTURE, NO_PARAMS, MAPPER);

        assertThat(summary).isNull();
    }

    @Test
    void aNullBodyWithAParamGivesTheParam() {
        JsonNode summary = ChangeSummary.build(null, "workflow_step.update", NO_CAPTURE, Map.of("id", "W1"), MAPPER);

        assertThat(summary).isEqualTo(tree("{\"id\": \"W1\"}"));
    }

    @Test
    void aBodyThatIsNotAnObjectOrArrayGivesOnlyTheParams() {
        JsonNode summary = ChangeSummary.build(
                "just text", "workflow_step.create", new String[] {"type"}, Map.of("id", "W1"), MAPPER);

        assertThat(summary).isEqualTo(tree("{\"id\": \"W1\"}"));
    }

    @Test
    void aParamIsAddedBesideTheCapturedFields() {
        JsonNode summary = ChangeSummary.build(
                tree("{\"type\": \"llm\", \"prompt\": \"secret\"}"),
                "workflow_step.create",
                new String[] {"type"},
                Map.of("id", "W1"),
                MAPPER);

        assertThat(summary).isEqualTo(tree("{\"type\": \"llm\", \"id\": \"W1\"}"));
    }
}
