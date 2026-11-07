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
    private static final String UPLOAD =
            "{\"key\":\"media\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"owner/upload\"}";
    private static final String COMPOSE = """
            {"label":"AI","outputInputs":[
              {"stepOrder":6,"outputName":"accompaniment.wav","inputKey":"music"},
              {"stepOrder":7,"outputName":"voice.wav","inputKey":"voice","optional":true},
              {"stepOrder":7,"outputName":"voice-manifest.json","inputKey":"manifest"}
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

    @Test
    void aSingleStoredUploadReferenceComesFirstThenTheDeclaredOutputs() throws Exception {
        String music = "runs/" + RUN_ID + "/steps/6/attempts/1/accompaniment.wav";
        String config = """
                {"outputInputs":[{"stepOrder":6,"outputName":"accompaniment.wav","inputKey":"music"}]}
                """;

        String resolved = resolver.resolve(UPLOAD, config, Map.of(6, outputs("accompaniment.wav", music)),
                RUN_ID, 8);

        assertThat(inputs(resolved)).containsExactly(
                new MediaJobInput("media", "flowcortex-uploads", "owner/upload"),
                new MediaJobInput("music", "media", music));
    }

    @Test
    void anObjectAnInlineStepStoredInTheUploadsBucketReachesALaterStep() throws Exception {
        String voice = "runs/" + RUN_ID + "/steps/7/voice.wav";
        String config = """
                {"outputInputs":[{"stepOrder":7,"outputName":"voice.wav","inputKey":"voice"}]}
                """;
        String recorded = "[{\"name\":\"voice.wav\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"" + voice
                + "\",\"mime\":\"audio/wav\",\"bytes\":3}]";

        String resolved = resolver.resolve(UPLOAD, config, Map.of(7, recorded), RUN_ID, 8);

        assertThat(inputs(resolved)).containsExactly(
                new MediaJobInput("media", "flowcortex-uploads", "owner/upload"),
                new MediaJobInput("voice", "flowcortex-uploads", voice));
    }

    @Test
    void anOptionalObjectTheStepDidNotRecordIsLeftOut() throws Exception {
        String music = "runs/" + RUN_ID + "/steps/6/attempts/1/accompaniment.wav";
        String manifest = "runs/" + RUN_ID + "/steps/7/voice-manifest.json";

        String resolved = resolver.resolve(UPLOAD, COMPOSE,
                Map.of(6, outputs("accompaniment.wav", music), 7, outputs("voice-manifest.json", manifest)),
                RUN_ID, 8);

        assertThat(inputs(resolved)).containsExactly(
                new MediaJobInput("media", "flowcortex-uploads", "owner/upload"),
                new MediaJobInput("music", "media", music),
                new MediaJobInput("manifest", "media", manifest));
    }

    @Test
    void anOptionalObjectOfAStepThatRecordedNothingIsLeftOut() throws Exception {
        String config = """
                {"outputInputs":[{"stepOrder":7,"outputName":"voice.wav","inputKey":"voice","optional":true}]}
                """;

        assertThat(inputs(resolver.resolve(UPLOAD, config, Map.of(), RUN_ID, 8)))
                .containsExactly(new MediaJobInput("media", "flowcortex-uploads", "owner/upload"));
    }

    @Test
    void aMissingObjectThatIsNotOptionalFailsWithContractViolationNamingTheStepAndTheName() {
        String vocals = "runs/" + RUN_ID + "/steps/6/attempts/1/vocals.wav";

        assertThatThrownBy(() -> resolver.resolve(UPLOAD, COMPOSE, Map.of(6, outputs("vocals.wav", vocals)),
                RUN_ID, 8))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("contract_violation");
                    assertThat(e.getMessage()).isEqualTo("Step 6 must have exactly one output named accompaniment.wav");
                });
    }

    @Test
    void anOptionalFlagThatIsNotABooleanIsAContractViolation() {
        String config = """
                {"outputInputs":[{"stepOrder":7,"outputName":"voice.wav","inputKey":"voice","optional":"yes"}]}
                """;

        String voice = outputs("voice.wav", "runs/" + RUN_ID + "/steps/7/voice.wav");

        assertThatThrownBy(() -> resolver.resolve(UPLOAD, config, Map.of(7, voice), RUN_ID, 8))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("contract_violation");
                    assertThat(e.getMessage()).contains("optional");
                });
    }

    private List<MediaJobInput> inputs(String resolved) throws Exception {
        return json.readValue(resolved, new TypeReference<List<MediaJobInput>>() { });
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
