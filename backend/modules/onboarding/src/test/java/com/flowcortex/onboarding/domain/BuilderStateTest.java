package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BuilderStateTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ObjectNode object(String json) {
        return (ObjectNode) MAPPER.readTree(json);
    }

    /** An incoming state whose merge over an empty state serializes to exactly {@code chars} characters. */
    private static JsonNode stateOfLength(int chars) {
        String overhead = "{\"k\":\"\"}";
        return MAPPER.readTree("{\"k\":\"" + "x".repeat(chars - overhead.length()) + "\"}");
    }

    @Test
    void shallowMergeKeepsEarlierKeys() {
        ObjectNode merged = BuilderState.merge(object("{\"welcomeSeen\":true,\"tip\":1}"), object("{\"tip\":2}"));

        assertThat(merged.toString()).isEqualTo("{\"welcomeSeen\":true,\"tip\":2}");
    }

    @Test
    void arrayStateIsRefused() {
        JsonNode array = MAPPER.readTree("[1,2]");
        InvalidOnboardingRequest error = catchThrowableOfType(InvalidOnboardingRequest.class,
                () -> BuilderState.merge(object("{}"), array));

        assertThat(error.errors()).containsEntry("state", "onboarding.builder.state.notObject");
    }

    @Test
    void missingStateIsRefused() {
        JsonNode jsonNull = MAPPER.readTree("null");

        assertThat(catchThrowableOfType(InvalidOnboardingRequest.class,
                () -> BuilderState.merge(object("{}"), null)).errors())
                .containsEntry("state", "onboarding.builder.state.notObject");
        assertThat(catchThrowableOfType(InvalidOnboardingRequest.class,
                () -> BuilderState.merge(object("{}"), jsonNull)).errors())
                .containsEntry("state", "onboarding.builder.state.notObject");
    }

    @Test
    void mergedStateAtTheCapIsAcceptedAndOneOverIsRefused() {
        ObjectNode atCap = BuilderState.merge(object("{}"), stateOfLength(BuilderState.MAX_STATE_CHARS));
        JsonNode oneOver = stateOfLength(BuilderState.MAX_STATE_CHARS + 1);

        assertThat(atCap.toString()).hasSize(8_192);
        assertThat(catchThrowableOfType(InvalidOnboardingRequest.class,
                () -> BuilderState.merge(object("{}"), oneOver)).errors())
                .containsEntry("state", "onboarding.builder.state.tooLarge");
    }

    @Test
    void unknownReasonIsRefused() {
        ObjectNode later = object("{\"reason\":\"later\"}");
        ObjectNode number = object("{\"reason\":3}");

        assertThat(catchThrowableOfType(InvalidOnboardingRequest.class,
                () -> BuilderState.withReason(object("{}"), later)).errors())
                .containsEntry("reason", "onboarding.builder.reason.unknown");
        assertThat(catchThrowableOfType(InvalidOnboardingRequest.class,
                () -> BuilderState.withReason(object("{}"), number)).errors())
                .containsKey("reason");
    }

    @Test
    void reasonIsRecordedInState() {
        assertThat(BuilderState.withReason(object("{\"a\":1}"), object("{\"reason\":\"skipped\"}")).toString())
                .isEqualTo("{\"a\":1,\"reason\":\"skipped\"}");
        assertThat(BuilderState.withReason(object("{\"a\":1}"), object("{}")).toString()).isEqualTo("{\"a\":1}");
        assertThat(BuilderState.withReason(object("{\"a\":1}"), object("{\"reason\":null}")).toString())
                .isEqualTo("{\"a\":1}");
    }
}
