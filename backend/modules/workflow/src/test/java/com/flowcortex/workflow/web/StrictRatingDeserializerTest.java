package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.web.WorkflowReviewsController.ReviewRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class StrictRatingDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private ReviewRequest read(String json) {
        return mapper.readValue(json, ReviewRequest.class);
    }

    @Test
    void aJsonNumberKeepsItsExactDecimalValue() {
        assertThat(read("{\"rating\":5}").rating()).isEqualByComparingTo("5");
        assertThat(read("{\"rating\":4.0}").rating()).isEqualByComparingTo("4");
        assertThat(read("{\"rating\":4.0000000000000000001}").rating())
                .isEqualTo(new BigDecimal("4.0000000000000000001"));
    }

    @Test
    void aMissingOrNullRatingIsNullForTheRulesToRefuse() {
        assertThat(read("{}").rating()).isNull();
        assertThat(read("{\"rating\":null}").rating()).isNull();
    }

    @Test
    void anyNonNumberTokenIsAMismatchNamingTheField() {
        for (String token : new String[] {"\"5\"", "\"yes\"", "true", "[4]", "{}"}) {
            assertThatThrownBy(() -> read("{\"rating\":" + token + "}")).as(token)
                    .isInstanceOfSatisfying(MismatchedInputException.class, e ->
                            assertThat(e.getPath().get(0).getPropertyName()).isEqualTo("rating"));
        }
    }
}
