package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.DataStoreJson;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DataStoreWireTest {

    @Test
    void aStoredDecimalCrossesToTheResponseTreeWithItsDigits() throws Exception {
        com.fasterxml.jackson.databind.JsonNode stored = DataStoreJson.mapper()
                .readTree("{\"pi\":3.141592653589793238462643,\"price\":1.00,\"huge\":1e400,\"n\":7}");

        tools.jackson.databind.JsonNode wire = DataStoreWire.toWire(stored);

        assertThat(wire.get("pi").decimalValue()).isEqualTo(new BigDecimal("3.141592653589793238462643"));
        assertThat(wire.get("price").decimalValue()).isEqualTo(new BigDecimal("1.00"));
        assertThat(wire.get("huge").decimalValue()).isEqualTo(new BigDecimal("1e400"));
        assertThat(wire.get("n").intValue()).isEqualTo(7);
    }
}
