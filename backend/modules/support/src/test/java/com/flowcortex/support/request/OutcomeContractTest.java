package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The assistant stores one set of outcomes; every schema that returns one must declare all of them. */
class OutcomeContractTest {

    private static final List<String> OUTCOMES = List.of("answered", "no_answer", "blocked", "handoff", "sign_in");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "com.flowcortex.support.assistant.AssistantService$ReplyMessage",
            "com.flowcortex.support.assistant.AssistantService$HistoryMessage",
            "com.flowcortex.support.request.RequestViews$ConversationMessage"})
    void everyOutcomeBearingSchemaDeclaresEveryStoredOutcome(String className) throws ReflectiveOperationException {
        Schema outcome = Class.forName(className).getDeclaredField("outcome").getAnnotation(Schema.class);

        assertThat(outcome.allowableValues()).containsExactlyElementsOf(OUTCOMES);
    }
}
