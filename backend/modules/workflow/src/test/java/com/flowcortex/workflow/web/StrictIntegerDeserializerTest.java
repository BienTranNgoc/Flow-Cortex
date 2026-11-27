package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class StrictIntegerDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private Integer revision(String json) {
        return mapper.readValue(json, EditStepOutputRequest.class).expectedRevision();
    }

    @Test
    void aWholeJsonNumberInRangeIsRead() {
        assertThat(revision("{\"expectedRevision\":2}")).isEqualTo(2);
        assertThat(revision("{\"expectedRevision\":0}")).isZero();
    }

    @Test
    void aMissingOrNullRevisionIsNullForValidationToRefuse() {
        assertThat(revision("{}")).isNull();
        assertThat(revision("{\"expectedRevision\":null}")).isNull();
    }

    @Test
    void anyOtherTokenIsAMismatchNamingTheField() {
        for (String token : new String[] {"1.5", "\"2\"", "true", "2.0", "2e0", "[2]", "{}", "2147483648"}) {
            assertThatThrownBy(() -> revision("{\"expectedRevision\":" + token + "}")).as(token)
                    .isInstanceOfSatisfying(MismatchedInputException.class, e ->
                            assertThat(e.getPath().get(0).getPropertyName()).isEqualTo("expectedRevision"));
        }
    }
}
