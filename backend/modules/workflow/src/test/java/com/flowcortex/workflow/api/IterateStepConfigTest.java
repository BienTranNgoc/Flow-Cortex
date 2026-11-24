package com.flowcortex.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class IterateStepConfigTest {

    @Test
    void defaultsApplied() {
        IterateStepConfig config = IterateStepConfig.parse("{\"child\":{\"type\":\"echo\"}}");

        assertThat(config.childType()).isEqualTo("echo");
        assertThat(config.childConfig()).isEqualTo("{}");
        assertThat(config.maxItems()).isEqualTo(50);
        assertThat(config.concurrency()).isEqualTo(3);
        assertThat(config.failFast()).isFalse();
        assertThat(IterateStepConfig.fault("{\"child\":{\"type\":\"echo\"}}")).isNull();
    }

    @Test
    void everyKeyIsReadWhenGiven() {
        IterateStepConfig config = IterateStepConfig.parse("{\"child\":{\"type\":\"translate\","
                + "\"config\":{\"lang\":\"vi\"}},\"maxItems\":7,\"concurrency\":2,\"onItemError\":\"fail\"}");

        assertThat(config.childType()).isEqualTo("translate");
        assertThat(config.childConfig()).isEqualTo("{\"lang\":\"vi\"}");
        assertThat(config.maxItems()).isEqualTo(7);
        assertThat(config.concurrency()).isEqualTo(2);
        assertThat(config.failFast()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"child\":{\"type\":\"echo\"},\"maxItems\":0}|maxItems",
            "{\"child\":{\"type\":\"echo\"},\"maxItems\":51}|maxItems",
            "{\"child\":{\"type\":\"echo\"},\"maxItems\":2.5}|maxItems",
            "{\"child\":{\"type\":\"echo\"},\"maxItems\":\"5\"}|maxItems",
            "{\"child\":{\"type\":\"echo\"},\"maxItems\":null}|maxItems",
            "{\"child\":{\"type\":\"echo\"},\"concurrency\":0}|concurrency",
            "{\"child\":{\"type\":\"echo\"},\"concurrency\":11}|concurrency",
            "{\"child\":{\"type\":\"echo\"},\"onItemError\":\"abort\"}|onItemError",
            "{\"child\":{\"type\":\"echo\"},\"onItemError\":1}|onItemError"})
    void boundsRefusedNamingTheField(String config, String field) {
        assertThat(IterateStepConfig.fault(config)).contains(field);
        assertThatThrownBy(() -> IterateStepConfig.parse(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "[]", "\"x\"", "{not json", "{}"})
    void missingOrMalformedConfigRefused(String config) {
        assertThat(IterateStepConfig.fault(config)).contains("child");
        assertThatThrownBy(() -> IterateStepConfig.parse(config)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownKeyRefusedNamingIt() {
        String fault = IterateStepConfig.fault("{\"child\":{\"type\":\"echo\"},\"source\":\"{step:1}\"}");

        assertThat(fault).contains("source");
    }

    @Test
    void anUnknownKeyOfTheChildIsRefusedNamingIt() {
        assertThat(IterateStepConfig.fault("{\"child\":{\"type\":\"echo\",\"cfg\":{}}}")).contains("child.cfg");
    }

    @Test
    void aFaultNeverEchoesAValueLongerThanTheKey() {
        String longValue = "x".repeat(300);

        assertThat(IterateStepConfig.fault("{\"child\":{\"type\":\"echo\"},\"" + longValue + "\":1}"))
                .doesNotContain(longValue);
        assertThat(IterateStepConfig.fault("{\"child\":{\"type\":\"echo\"},\"onItemError\":\"" + longValue + "\"}"))
                .doesNotContain(longValue);
        assertThat(IterateStepConfig.fault(longValue)).doesNotContain(longValue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"child\":null}", "{\"child\":\"echo\"}", "{\"child\":[]}", "{\"child\":{}}",
            "{\"child\":{\"type\":\"\"}}", "{\"child\":{\"type\":\"  \"}}", "{\"child\":{\"type\":1}}",
            "{\"child\":{\"type\":null}}"})
    void childTypeMustBeANonEmptyString(String config) {
        assertThat(IterateStepConfig.fault(config)).contains("child");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"x\"", "5", "null"})
    void childConfigMustBeAnObject(String childConfig) {
        String config = "{\"child\":{\"type\":\"echo\",\"config\":" + childConfig + "}}";

        assertThat(IterateStepConfig.fault(config)).contains("child.config");
    }

    @Test
    void childTypesAreTheClosedList() {
        assertThat(IterateStepConfig.CHILD_TYPES).containsExactlyInAnyOrder("echo", "fail", "translate", "tts");
        assertThat(IterateStepConfig.TYPE).isEqualTo("iterate");
    }

    @Test
    void effectiveTypesExpandsToMaxItemsChildren() {
        List<String> types = IterateStepConfig.effectiveTypes("iterate",
                "{\"child\":{\"type\":\"translate\"},\"maxItems\":10}");

        assertThat(types).hasSize(11);
        assertThat(types.get(0)).isEqualTo("iterate");
        assertThat(types.subList(1, 11)).isEqualTo(Collections.nCopies(10, "translate"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "{}", "{\"child\":{\"type\":\"echo\"},\"maxItems\":99}", "{not json"})
    void effectiveTypesKeepsAnUnreadableConfigAsIterateAlone(String config) {
        assertThat(IterateStepConfig.effectiveTypes("iterate", config)).containsExactly("iterate");
    }

    @Test
    void effectiveTypesPassesOtherTypesThrough() {
        assertThat(IterateStepConfig.effectiveTypes("echo", null)).containsExactly("echo");
        assertThat(IterateStepConfig.effectiveTypes("translate", "{\"child\":{\"type\":\"echo\"}}"))
                .containsExactly("translate");
    }
}
