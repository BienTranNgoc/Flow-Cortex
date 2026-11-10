package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowCallConfigTest {

    private static final String ID = "7b1e0c52-3f43-4a8e-9c55-0d6a2b1f9e10";

    @Test
    void aWorkflowIdAloneParsesToThatUuid() {
        WorkflowCallConfig.Result result = WorkflowCallConfig.parse("{\"workflowId\":\"" + ID + "\"}");

        assertThat(result.fault()).isNull();
        assertThat(result.config().workflowId()).isEqualTo(UUID.fromString(ID));
        assertThat(WorkflowCallConfig.fault("{\"workflowId\":\"" + ID + "\"}")).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "[]", "\"x\"", "{not json", "{}", "{\"workflowId\":\"abc\"}",
            "{\"workflowId\":42}", "{\"workflowId\":null}", "{\"workflowId\":\"1-1-1-1-1\"}"})
    void anyOtherConfigNamesTheWorkflowIdField(String config) {
        WorkflowCallConfig.Result result = WorkflowCallConfig.parse(config);

        assertThat(result.config()).isNull();
        assertThat(result.fault()).contains("workflowId");
        assertThat(WorkflowCallConfig.fault(config)).isEqualTo(result.fault());
    }

    @Test
    void anUnknownKeyIsNamed() {
        String fault = WorkflowCallConfig.fault("{\"workflowId\":\"" + ID + "\",\"inputMapping\":{}}");

        assertThat(fault).contains("inputMapping");
    }

    @Test
    void aFaultNeverEchoesAValueLongerThanTheKey() {
        String longValue = "x".repeat(300);

        assertThat(WorkflowCallConfig.fault("{\"workflowId\":\"" + longValue + "\"}")).doesNotContain(longValue);
        assertThat(WorkflowCallConfig.fault("{\"" + longValue + "\":1}")).doesNotContain(longValue);
        assertThat(WorkflowCallConfig.fault(longValue)).doesNotContain(longValue);
    }
}
