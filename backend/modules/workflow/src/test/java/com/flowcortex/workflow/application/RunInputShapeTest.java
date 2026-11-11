package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunInputShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MediaJobInput input(String key) {
        return new MediaJobInput(key, "media", "runs/r/inputs/" + key);
    }

    @Test
    void noBindingsIsNull() {
        assertThat(RunInputShape.storedInput(List.of())).isNull();
    }

    @Test
    void theOneMediaBindingIsAnObject() throws Exception {
        JsonNode json = MAPPER.readTree(RunInputShape.storedInput(List.of(input("media"))));

        assertThat(json.isObject()).isTrue();
        assertThat(json.get("key").asText()).isEqualTo("media");
        assertThat(json.get("objectKey").asText()).isEqualTo("runs/r/inputs/media");
    }

    @Test
    void imageBindingsAreAnArrayInPositionOrder() throws Exception {
        JsonNode json = MAPPER.readTree(RunInputShape.storedInput(List.of(input("image_1"), input("image_2"))));

        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(2);
        assertThat(json.get(0).get("key").asText()).isEqualTo("image_1");
        assertThat(json.get(1).get("key").asText()).isEqualTo("image_2");
    }

    @Test
    void aSingleImageBindingIsAnArrayOfOne() throws Exception {
        JsonNode json = MAPPER.readTree(RunInputShape.storedInput(List.of(input("image_1"))));

        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(1);
    }
}
