package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PutDataEntryRequestTest {

    @Test
    void aValueOfAnyJsonTypeIsKept() {
        assertThat(PutDataEntryRequest.parse("{\"value\":{\"a\":[1,\"x\"]}}").value().toString())
                .isEqualTo("{\"a\":[1,\"x\"]}");
        assertThat(PutDataEntryRequest.parse("{\"value\":null}").value().isNull()).isTrue();
        assertThat(PutDataEntryRequest.parse("{\"value\":false}").value().isBoolean()).isTrue();
    }

    @Test
    void aDecimalKeepsItsDigitsItsTrailingZerosAndItsRange() {
        JsonNode value = PutDataEntryRequest
                .parse("{\"value\":{\"pi\":3.141592653589793238462643,\"price\":1.00,\"huge\":1e400}}").value();

        assertThat(value.get("pi").decimalValue()).isEqualTo(new BigDecimal("3.141592653589793238462643"));
        assertThat(value.get("price").decimalValue()).isEqualTo(new BigDecimal("1.00"));
        assertThat(value.get("huge").decimalValue()).isEqualTo(new BigDecimal("1e400"));
    }

    @Test
    void theDataTypeIsTextWhenGivenAndNullOtherwise() {
        assertThat(PutDataEntryRequest.parse("{\"value\":1,\"dataType\":\"template\"}").dataType())
                .isEqualTo("template");
        assertThat(PutDataEntryRequest.parse("{\"value\":1,\"dataType\":null}").dataType()).isNull();
        assertThat(PutDataEntryRequest.parse("{\"value\":1}").dataType()).isNull();
    }

    @Test
    void anythingButAnObjectWithAValueAndATextTypeIsABadRequest() {
        for (String body : new String[] {null, "", "  ", "null", "[]", "42", "\"v\"", "{}", "{\"dataType\":\"json\"}",
                "{\"value\":1,\"dataType\":5}", "{\"value\":1,\"dataType\":{}}", "{\"value\":", "{\"value\":1} x",
                "{\"value\":1e99999999999}"}) {
            assertThatThrownBy(() -> PutDataEntryRequest.parse(body)).as("body %s", body)
                    .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getDetailMessageCode()).isEqualTo("workflow.dataStore.bodyInvalid");
                    });
        }
    }
}
