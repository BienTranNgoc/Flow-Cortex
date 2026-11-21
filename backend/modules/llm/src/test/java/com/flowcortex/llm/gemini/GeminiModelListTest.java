package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.registry.RegisteredProvider.VendorModel;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModels;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GeminiModelListTest {

    private static VendorModels parse(String json) {
        return GeminiModelList.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String model(String name, String displayName, String... methods) {
        String list = String.join(",", Arrays.stream(methods).map(m -> "\"" + m + "\"").toList());
        String display = displayName == null ? "" : ",\"displayName\":\"" + displayName + "\"";
        return "{\"name\":\"models/" + name + "\"" + display + ",\"supportedGenerationMethods\":[" + list + "]}";
    }

    @Test
    void keepsGeminiModelsThatGenerateContentAndStripsThePrefix() {
        VendorModels models = parse("{\"models\":[" + model("gemini-3.8-flash", "Gemini 3.8 Flash",
                "generateContent", "countTokens") + "]}");

        assertThat(models.failure()).isNull();
        assertThat(models.models()).containsExactly(new VendorModel("gemini-3.8-flash", "Gemini 3.8 Flash"));
    }

    @Test
    void dropsModelsThatAreNotGeminiTextGenerators() {
        VendorModels models = parse("{\"models\":["
                + model("text-embedding-004", "Embedding", "generateContent") + ","
                + model("gemini-3.8-pro", "No generate", "countTokens") + ","
                + model("gemini-3.8-flash-tts", "TTS", "generateContent") + ","
                + model("gemini-2.5-flash-image", "Image", "generateContent") + ","
                + model("gemini-live-2.5-flash", "Live", "generateContent") + ","
                + model("gemini-embedding-001", "Emb", "generateContent") + ","
                + model("gemini-3.8-flash", "Flash", "generateContent") + "]}");

        assertThat(models.models()).extracting(VendorModel::id).containsExactly("gemini-3.8-flash");
    }

    @Test
    void fallsBackToTheIdWhenThereIsNoDisplayName() {
        VendorModels models = parse("{\"models\":[" + model("gemini-3.8-flash", null, "generateContent") + "]}");

        assertThat(models.models()).containsExactly(new VendorModel("gemini-3.8-flash", "gemini-3.8-flash"));
    }

    @Test
    void sortsByIdAndIgnoresTheNextPageToken() {
        VendorModels models = parse("{\"nextPageToken\":\"more\",\"models\":["
                + model("gemini-3.8-flash", "B", "generateContent") + ","
                + model("gemini-3.5-flash-lite", "A", "generateContent") + "]}");

        assertThat(models.models()).extracting(VendorModel::id)
                .containsExactly("gemini-3.5-flash-lite", "gemini-3.8-flash");
        assertThat(models.failure()).isNull();
    }

    @Test
    void anEmptyAnswerIsAnEmptyListNotAFailure() {
        assertThat(parse("{}").models()).isEmpty();
        assertThat(parse("{}").failure()).isNull();
    }

    @Test
    void anUnparsableBodyIsAnUpstreamFailure() {
        VendorModels models = parse("<html>oops");

        assertThat(models.models()).isEmpty();
        assertThat(models.failure()).isEqualTo("upstream");
    }

    @Test
    void aBodyThatIsNotAnObjectIsAnUpstreamFailure() {
        assertThat(parse("[1,2]").failure()).isEqualTo("upstream");
        assertThat(GeminiModelList.parse(null).failure()).isEqualTo("upstream");
    }
}
