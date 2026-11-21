package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class QuizRequestParserTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static QuizRequest parse(String json) {
        return QuizRequestParser.parse(MAPPER.readTree(json));
    }

    private static InvalidOnboardingRequest refused(String json) {
        JsonNode body = MAPPER.readTree(json);
        return catchThrowableOfType(InvalidOnboardingRequest.class, () -> QuizRequestParser.parse(body));
    }

    @Test
    void absentFieldIsAbsent() {
        QuizRequest request = parse("{}");

        assertThat(request.aiLevel()).isEqualTo(Answer.absent());
        assertThat(request.industries()).isEqualTo(Answer.absent());
        assertThat(request.finished()).isFalse();
        assertThat(request.skipped()).isFalse();
    }

    @Test
    void nullFieldIsSkipped() {
        QuizRequest request = parse("{\"source\":null,\"purposes\":null}");

        assertThat(request.source()).isEqualTo(Answer.skipped());
        assertThat(request.purposes()).isEqualTo(Answer.skipped());
    }

    @Test
    void valueFieldIsStored() {
        QuizRequest request = parse("{\"aiLevel\":\"beginner\",\"purposes\":[\"research\",\"automation\"]}");

        assertThat(request.aiLevel()).isEqualTo(Answer.value("beginner"));
        assertThat(request.purposes()).isEqualTo(Answer.value(List.of("research", "automation")));
    }

    @Test
    void unknownAiLevelIsRefusedNamingTheField() {
        InvalidOnboardingRequest error = refused("{\"aiLevel\":\"expert\"}");

        assertThat(error.errors()).containsOnlyKeys("aiLevel");
        assertThat(error.errors().get("aiLevel")).isEqualTo("onboarding.field.unknownValue");
    }

    @Test
    void emptyListIsRefused() {
        assertThat(refused("{\"purposes\":[]}").errors()).containsEntry("purposes", "onboarding.field.emptyList");
    }

    @Test
    void duplicateListIsRefused() {
        assertThat(refused("{\"contentTypes\":[\"text\",\"text\"]}").errors())
                .containsEntry("contentTypes", "onboarding.field.duplicate");
    }

    @Test
    void listLongerThanVocabularyIsRefused() {
        InvalidOnboardingRequest error = refused(
                "{\"industries\":[\"marketing\",\"sales\",\"finance\",\"hr\",\"tech\",\"other\",\"marketing\"]}");

        assertThat(error.errors()).containsEntry("industries", "onboarding.field.tooMany");
    }

    @Test
    void wrongJsonTypeIsRefused() {
        InvalidOnboardingRequest error = refused(
                "{\"aiLevel\":3,\"purposes\":\"research\",\"contentTypes\":[1],\"source\":[\"google\"]}");

        assertThat(error.errors()).containsOnlyKeys("aiLevel", "purposes", "contentTypes", "source");
        assertThat(error.errors().values()).containsOnly("onboarding.field.wrongType");
    }

    @Test
    void oneBadFieldRefusesTheWholeBody() {
        InvalidOnboardingRequest error = refused("{\"aiLevel\":\"expert\",\"source\":\"google\",\"usageType\":\"x\"}");

        assertThat(error.errors()).containsOnlyKeys("aiLevel", "usageType");
    }

    @Test
    void industriesComeBackInVocabularyOrderWithOtherLast() {
        QuizRequest request = parse("{\"industries\":[\"other\",\"tech\",\"sales\"]}");

        assertThat(request.industries()).isEqualTo(Answer.value(List.of("sales", "tech", "other")));
    }

    @Test
    void skippedAndFinishedMustBeBooleans() {
        assertThat(parse("{\"skipped\":true,\"finished\":true}").skipped()).isTrue();

        InvalidOnboardingRequest error = refused("{\"skipped\":\"yes\",\"finished\":null}");

        assertThat(error.errors()).containsEntry("skipped", "onboarding.field.wrongType");
        assertThat(error.errors()).containsEntry("finished", "onboarding.field.notNull");
    }

    @Test
    void unknownPropertiesAreIgnoredAndANonObjectBodyIsRefused() {
        assertThat(parse("{\"nonsense\":1}").aiLevel()).isEqualTo(Answer.absent());
        assertThat(refused("[1]").errors()).containsEntry("body", "onboarding.field.wrongType");
    }
}
