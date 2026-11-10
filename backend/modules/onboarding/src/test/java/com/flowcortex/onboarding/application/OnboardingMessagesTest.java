package com.flowcortex.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.onboarding.domain.BuilderState;
import com.flowcortex.onboarding.domain.InvalidOnboardingRequest;
import com.flowcortex.onboarding.domain.ProgressRequestParser;
import com.flowcortex.onboarding.domain.QuizRequestParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OnboardingMessagesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyKeyAParserCanRecordHasAMessage() {
        List<String> keys = new ArrayList<>();
        keys.addAll(refused(() -> QuizRequestParser.parse(JSON.readTree(
                "{\"aiLevel\":\"expert\",\"purposes\":[],\"contentTypes\":[\"text\",\"text\"],"
                        + "\"industries\":\"sales\",\"finished\":null}"))));
        keys.addAll(refused(() -> QuizRequestParser.parse(JSON.readTree(
                "{\"contentTypes\":[\"text\",\"image\",\"audio\",\"video\",\"spreadsheet\",\"document\","
                        + "\"slide\",\"text\"]}"))));
        keys.addAll(refused(() -> ProgressRequestParser.parse(JSON.readTree("{\"question\":7,\"step\":null}"))));
        keys.addAll(refused(() -> BuilderState.merge(JSON.createObjectNode(), JSON.readTree("[1]"))));
        keys.addAll(refused(() -> BuilderState.merge(JSON.createObjectNode(),
                JSON.readTree("{\"blob\":\"" + "x".repeat(BuilderState.MAX_STATE_CHARS) + "\"}"))));
        keys.addAll(refused(() -> BuilderState.withReason(JSON.createObjectNode(), JSON.readTree("{\"reason\":\"x\"}"))));

        assertThat(keys).containsExactlyInAnyOrder(
                "onboarding.field.unknownValue", "onboarding.field.emptyList", "onboarding.field.duplicate",
                "onboarding.field.wrongType", "onboarding.field.notNull", "onboarding.field.tooMany",
                "onboarding.field.outOfRange", "onboarding.field.notNull", "onboarding.builder.state.notObject",
                "onboarding.builder.state.tooLarge", "onboarding.builder.reason.unknown");
        keys.forEach(key -> assertThat(OnboardingMessages.of(key).code()).isEqualTo(key));
    }

    @Test
    void anUnmappedKeyFailsLoudly() {
        assertThatThrownBy(() -> OnboardingMessages.of("onboarding.field.nope"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<String> refused(Supplier<?> parse) {
        try {
            parse.get();
        } catch (InvalidOnboardingRequest ex) {
            return new ArrayList<>(ex.errors().values());
        }
        throw new AssertionError("expected the body to be refused");
    }
}
