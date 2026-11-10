package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProgressRequestParserTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-10T09:30:00Z");

    private static ProgressPatch parse(String json) {
        return ProgressRequestParser.parse(MAPPER.readTree(json));
    }

    private static InvalidOnboardingRequest refused(String json) {
        JsonNode body = MAPPER.readTree(json);
        return catchThrowableOfType(InvalidOnboardingRequest.class, () -> ProgressRequestParser.parse(body));
    }

    @Test
    void allFieldsOptional() {
        ProgressPatch patch = parse("{}");

        assertThat(patch.step()).isNull();
        assertThat(patch.question()).isNull();
        assertThat(patch.surveyDeferred()).isFalse();
        assertThat(patch.finishing()).isFalse();
        JsonNode json = patch.toJson(NOW);
        assertThat(json.get("v").asInt()).isEqualTo(2);
        assertThat(json.get("updatedAt").asString()).isEqualTo("2026-11-10T09:30:00Z");
        assertThat(json.has("step")).isFalse();
        assertThat(json.has("question")).isFalse();
        assertThat(json.has("surveyDeferredAt")).isFalse();
    }

    @Test
    void stepAndQuestionAreCarriedIntoThePatch() {
        JsonNode json = parse("{\"step\":\"quiz\",\"question\":3}").toJson(NOW);

        assertThat(json.get("step").asString()).isEqualTo("quiz");
        assertThat(json.get("question").asInt()).isEqualTo(3);
    }

    @Test
    void nullStepIsRefused() {
        assertThat(refused("{\"step\":null}").errors()).containsEntry("step", "onboarding.field.notNull");
    }

    @Test
    void unknownStepIsRefused() {
        assertThat(refused("{\"step\":\"library\"}").errors()).containsEntry("step", "onboarding.field.unknownValue");
        assertThat(refused("{\"step\":4}").errors()).containsEntry("step", "onboarding.field.wrongType");
    }

    @Test
    void questionOutsideOneToSixIsRefused() {
        assertThat(refused("{\"step\":\"quiz\",\"question\":7}").errors())
                .containsEntry("question", "onboarding.field.outOfRange");
        assertThat(refused("{\"question\":0}").errors()).containsEntry("question", "onboarding.field.outOfRange");
        assertThat(refused("{\"question\":\"2\"}").errors()).containsEntry("question", "onboarding.field.wrongType");
        assertThat(refused("{\"question\":2.5}").errors()).containsEntry("question", "onboarding.field.wrongType");
        assertThat(refused("{\"question\":null}").errors()).containsEntry("question", "onboarding.field.notNull");
    }

    @Test
    void finishedAtInBodyIsIgnored() {
        ProgressPatch patch = parse("{\"step\":\"done\",\"finishedAt\":\"2020-01-01T00:00:00Z\"}");

        assertThat(patch.toJson(NOW).has("finishedAt")).isFalse();
        assertThat(patch.finishing()).isFalse();
    }

    @Test
    void surveyDeferredTrueBecomesAStampFlag() {
        ProgressPatch patch = parse("{\"step\":\"done\",\"surveyDeferred\":true,\"finished\":true}");

        assertThat(patch.surveyDeferred()).isTrue();
        assertThat(patch.finishing()).isTrue();
        assertThat(patch.toJson(NOW).get("surveyDeferredAt").asString()).isEqualTo("2026-11-10T09:30:00Z");
        assertThat(parse("{\"surveyDeferred\":false}").toJson(NOW).has("surveyDeferredAt")).isFalse();
    }

    @Test
    void booleanFieldsRefuseOtherTypes() {
        assertThat(refused("{\"finished\":\"yes\",\"surveyDeferred\":null}").errors())
                .containsEntry("finished", "onboarding.field.wrongType")
                .containsEntry("surveyDeferred", "onboarding.field.notNull");
    }

    @Test
    void aNonObjectBodyIsRefused() {
        assertThat(refused("[]").errors()).containsKey("body");
    }
}
