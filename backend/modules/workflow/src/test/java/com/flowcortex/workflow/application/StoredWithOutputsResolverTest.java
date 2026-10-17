package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoredWithOutputsResolverTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final String STORED = """
            [{"key":"image_1","bucket":"uploads","objectKey":"owner/image-1"},
             {"key":"image_2","bucket":"uploads","objectKey":"owner/image-2"}]
            """;
    private static final String CONFIG = """
            {"profile":"vertical_ad_v1","outputInputs":[
              {"stepOrder":3,"outputName":"speech.wav","inputKey":"speech"}
            ]}
            """;
    private final StoredWithOutputsResolver resolver = new StoredWithOutputsResolver();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void appendsTheNamedOutputAfterStoredInputs() throws Exception {
        String speechKey = "runs/" + RUN_ID + "/steps/3/attempts/1/speech.wav";
        String resolved = resolver.resolve(STORED, CONFIG, Map.of(3, outputs("speech.wav", speechKey)), RUN_ID, 4);

        assertThat(json.readValue(resolved, new TypeReference<List<MediaJobInput>>() { }))
                .containsExactly(
                        new MediaJobInput("image_1", "uploads", "owner/image-1"),
                        new MediaJobInput("image_2", "uploads", "owner/image-2"),
                        new MediaJobInput("speech", "media", speechKey));
    }

    @Test
    void rejectsMissingDuplicateAndForeignOutputs() {
        assertThatThrownBy(() -> resolver.resolve(STORED, CONFIG, Map.of(), RUN_ID, 4))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("has no accepted outputs");

        String duplicates = "[" + output("speech.wav", validKey()) + "," + output("speech.wav", validKey()) + "]";
        assertThatThrownBy(() -> resolver.resolve(STORED, CONFIG, Map.of(3, duplicates), RUN_ID, 4))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("exactly one output");

        String foreignKey = "runs/" + UUID.randomUUID() + "/steps/3/speech.wav";
        assertThatThrownBy(() -> resolver.resolve(STORED, CONFIG,
                Map.of(3, outputs("speech.wav", foreignKey)), RUN_ID, 4))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("outside its run prefix");
    }

    @Test
    void rejectsAnInputKeyThatDuplicatesAStoredBinding() {
        String config = CONFIG.replace("\"speech\"", "\"image_1\"");

        assertThatThrownBy(() -> resolver.resolve(STORED, config, Map.of(3, outputs("speech.wav", validKey())),
                RUN_ID, 4))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("unique");
    }

    private static String outputs(String name, String key) {
        return "[" + output(name, key) + "]";
    }

    private static String output(String name, String key) {
        return "{\"name\":\"" + name + "\",\"bucket\":\"media\",\"objectKey\":\"" + key
                + "\",\"mime\":\"audio/wav\",\"bytes\":42}";
    }

    private static String validKey() {
        return "runs/" + RUN_ID + "/steps/3/attempts/1/speech.wav";
    }
}
