package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.KEY;
import static com.flowcortex.workflow.application.DubFixtures.context;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.application.provider.GeminiClient;
import com.flowcortex.workflow.application.provider.GeminiException;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** {@code tts}: every reviewed line voiced in one request, stored as one voice file plus its manifest. */
class TtsStepExecutorTest {

    private static final byte[] WAV = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
    private static final String VOICE_MODEL = "gemini-3.8-flash-tts";
    private static final String LITE_VOICE_MODEL = "gemini-3.8-flash-lite-tts";
    /** 44 characters: 3.4 s at 13/s, over 1.2 x a 2.6 s slot. */
    private static final String LONG_LINE = "Xin chào các bạn hôm nay mình đi chợ sớm nhé";
    /** 40 characters: 3.08 s at 13/s, just under 1.2 x a 2.6 s slot. */
    private static final String FITTING_LINE = "Xin chào các bạn hôm nay mình đi chợ nhé";

    private final GeminiClient gemini = mock(GeminiClient.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final Map<String, String> storedText = new LinkedHashMap<>();

    @BeforeEach
    void storeEverything() {
        when(mediaJobs.putRunObject(any(), anyInt(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(2);
            byte[] bytes = invocation.getArgument(4);
            storedText.put(name, new String(bytes, StandardCharsets.UTF_8));
            return new MediaOutput(name, "flowcortex-uploads", "runs/r/steps/7/" + name, invocation.getArgument(3),
                    (long) bytes.length, null);
        });
        when(gemini.speak(anyString(), anyString())).thenReturn(new GeminiClient.Speech(WAV, VOICE_MODEL));
    }

    private static String segment(int index, long startMs, long endMs, String vi, Boolean edited) {
        return "{\"index\":" + index + ",\"startMs\":" + startMs + ",\"endMs\":" + endMs + ",\"zh\":\"中文" + index
                + "\",\"vi\":\"" + vi + "\"" + (edited == null ? "" : ",\"edited\":" + edited) + "}";
    }

    private static String reviewed(String... segments) {
        return "{\"segments\":[" + String.join(",", segments) + "]}";
    }

    private TtsStepExecutor executor() {
        return new TtsStepExecutor(gemini, mediaJobs, dub());
    }

    @Test
    void everyNonEmptyLineIsVoicedInOneRequestInSegmentOrderWithTheMappedVoice() {
        StepContext context = context("tts", 7, reviewed(
                segment(1, 500, 3100, "Xin chào", false),
                segment(2, 3500, 4500, "", false),
                segment(3, 5000, 9800, "Tạm biệt", false)), "male");

        String output = executor().execute(context);

        verify(gemini, times(1)).speak("Xin chào <long pause> Tạm biệt", "Charon");
        verify(gemini, never()).generateJson(anyString(), anyString());
        assertThat(output).isEqualTo("{\"voiced\":2}");
        assertThat(storedText.get("voice-manifest.json")).isEqualTo("{\"voice\":\"Charon\",\"model\":\""
                + VOICE_MODEL + "\",\"segments\":[{\"index\":1,\"startMs\":500,\"endMs\":3100,\"vi\":\"Xin chào\"},"
                + "{\"index\":3,\"startMs\":5000,\"endMs\":9800,\"vi\":\"Tạm biệt\"}]}");
    }

    @Test
    void theVoiceFileAndTheManifestAreStoredAndRecordedAsTheStepsOutputs() {
        StepContext context = context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female");

        executor().execute(context);

        verify(mediaJobs).putRunObject(context.runId(), 7, "voice.wav", "audio/wav", WAV);
        verify(mediaJobs).putRunObject(eq(context.runId()), eq(7), eq("voice-manifest.json"),
                eq("application/json"), any());
        assertThat(context.storedOutputs()).extracting(MediaOutput::name)
                .containsExactly("voice.wav", "voice-manifest.json");
        verify(gemini).speak("Xin chào", "Kore");
    }

    @Test
    void uneditedLinesExpectedLongerThanTheirSlotAreShortenedInOneRequestBeforeVoicing() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(
                "[{\"index\":1,\"vi\":\"Chào cả nhà\"},{\"index\":3,\"vi\":\"Đi chợ sớm\"}]");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        StepContext context = context("tts", 7, reviewed(
                segment(1, 500, 3100, LONG_LINE, null),
                segment(2, 3500, 9800, "Vừa vặn", false),
                segment(3, 10000, 12600, LONG_LINE, false)), "female");

        executor().execute(context);

        verify(gemini, times(1)).generateJson(prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("1 (max 33 characters): " + LONG_LINE)
                .contains("3 (max 33 characters): " + LONG_LINE).doesNotContain("Vừa vặn");
        verify(gemini).speak("Chào cả nhà <long pause> Vừa vặn <long pause> Đi chợ sớm", "Kore");
        assertThat(storedText.get("voice-manifest.json")).contains("\"vi\":\"Chào cả nhà\"")
                .contains("\"vi\":\"Đi chợ sớm\"").doesNotContain(LONG_LINE);
    }

    @Test
    void aLineJustUnderTheOverrunThresholdIsVoicedAsWritten() {
        executor().execute(context("tts", 7, reviewed(segment(1, 500, 3100, FITTING_LINE, false)), "female"));

        verify(gemini, never()).generateJson(anyString(), anyString());
        verify(gemini).speak(FITTING_LINE, "Kore");
    }

    @Test
    void aLineTheMemberEditedIsNeverReworded() {
        StepContext context = context("tts", 7, "[" + segment(1, 500, 3100, LONG_LINE, true) + "]", "female");

        executor().execute(context);

        verify(gemini, never()).generateJson(anyString(), anyString());
        verify(gemini).speak(LONG_LINE, "Kore");
        assertThat(storedText.get("voice-manifest.json")).contains("\"vi\":\"" + LONG_LINE + "\"");
    }

    @Test
    void aShorteningAnswerThatIsMissingOrNotShorterKeepsTheMembersLine() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn("[{\"index\":1,\"vi\":\"" + LONG_LINE
                + " nữa\"}]");

        executor().execute(context("tts", 7, reviewed(
                segment(1, 500, 3100, LONG_LINE, false),
                segment(2, 10000, 12600, LONG_LINE, false)), "female"));

        verify(gemini).speak(LONG_LINE + " <long pause> " + LONG_LINE, "Kore");
    }

    @Test
    void aStoreFailureFailsTheStepStoreUnavailable() {
        doThrow(new MediaStoreUnavailableException("store refused the write", null)).when(mediaJobs)
                .putRunObject(any(), anyInt(), anyString(), anyString(), any());

        assertThatThrownBy(() -> executor().execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("store_unavailable"));
    }

    @Test
    void aVoiceQuotaRefusalFailsProviderQuotaExceededAndStoresNothing() {
        when(gemini.speak(anyString(), anyString()))
                .thenThrow(new GeminiException(GeminiException.Reason.QUOTA_EXCEEDED, "gemini-3.8-flash-lite-tts"));

        assertThatThrownBy(() -> executor().execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        assertThat(storedText).isEmpty();
    }

    @Test
    void withEveryLineEmptiedNoVoiceIsRequestedAndOnlyAnEmptyManifestIsStored() {
        StepContext context = context("tts", 7, reviewed(segment(1, 500, 3100, "", false)), "female");

        String output = executor().execute(context);

        verify(gemini, never()).speak(anyString(), anyString());
        assertThat(output).isEqualTo("{\"voiced\":0}");
        assertThat(storedText).containsOnlyKeys("voice-manifest.json");
        assertThat(storedText.get("voice-manifest.json"))
                .isEqualTo("{\"voice\":\"Kore\",\"model\":null,\"segments\":[]}");
    }

    @Test
    void whileUnavailableItFailsDubDisabledWithoutAProviderCall() {
        TtsStepExecutor off = new TtsStepExecutor(gemini, mediaJobs, dub(true, "", 13));

        assertThat(off.available()).isFalse();
        assertThatThrownBy(() -> off.execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("dub_disabled"));
        verify(gemini, never()).speak(anyString(), anyString());
        verify(gemini, never()).generateJson(anyString(), anyString());
        assertThat(new TtsStepExecutor(gemini, mediaJobs, dub(true, KEY, 13)).available()).isTrue();
    }

    @Test
    void whenTheVoiceModelStaysDownTheLiteVoiceModelVoicesTheRunAndTheManifestRecordsIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer provider = MockRestServiceServer.bindTo(builder).build();
        for (int i = 0; i < 3; i++) {
            provider.expect(requestTo("https://gemini.test/v1beta/interactions"))
                    .andExpect(jsonPath("$.model").value(VOICE_MODEL))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        provider.expect(requestTo("https://gemini.test/v1beta/interactions"))
                .andExpect(jsonPath("$.model").value(LITE_VOICE_MODEL))
                .andExpect(jsonPath("$.input").value("Xin chào"))
                .andRespond(withSuccess("{\"steps\":[{\"content\":[{\"type\":\"audio\",\"data\":\""
                        + Base64.getEncoder().encodeToString(WAV) + "\"}]}]}", MediaType.APPLICATION_JSON));
        DubProperties dub = dub();
        GeminiClient client = new GeminiClient(builder, dub.gemini(), wait -> { }, ObservationRegistry.NOOP);

        String output = new TtsStepExecutor(client, mediaJobs, dub)
                .execute(context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female"));

        provider.verify();
        assertThat(output).isEqualTo("{\"voiced\":1}");
        verify(mediaJobs).putRunObject(any(), eq(7), eq("voice.wav"), eq("audio/wav"), eq(WAV));
        assertThat(storedText.get("voice-manifest.json")).contains("\"model\":\"" + LITE_VOICE_MODEL + "\"");
    }

    @Test
    void duplicateSegmentIndexesFailContractViolationWithoutAProviderCall() {
        assertThatThrownBy(() -> executor().execute(context("tts", 7, reviewed(
                segment(1, 500, 3100, "Xin chào", false),
                segment(1, 3500, 4500, "Tạm biệt", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
        verify(gemini, never()).speak(anyString(), anyString());
        verify(gemini, never()).generateJson(anyString(), anyString());
        assertThat(storedText).isEmpty();
    }

    @Test
    void aDecomposedLineIsMeasuredAndVoicedInItsComposedForm() {
        String decomposed = Normalizer.normalize(FITTING_LINE, Normalizer.Form.NFD);

        executor().execute(context("tts", 7, reviewed(segment(1, 500, 3100, decomposed, false)), "female"));

        verify(gemini, never()).generateJson(anyString(), anyString());
        verify(gemini).speak(FITTING_LINE, "Kore");
        assertThat(storedText.get("voice-manifest.json")).contains("\"vi\":\"" + FITTING_LINE + "\"");
    }

    @Test
    void inlineTagsTheMemberTypedAreLeftOutOfTheVoicedTextAndTheManifest() {
        String output = executor().execute(context("tts", 7, reviewed(
                segment(1, 500, 3100, "Xin chào <long pause> các bạn", false),
                segment(2, 3500, 4500, "<long pause>", false),
                segment(3, 5000, 9800, "<whisper>Tạm biệt", false)), "female"));

        verify(gemini).speak("Xin chào các bạn <long pause> Tạm biệt", "Kore");
        assertThat(output).isEqualTo("{\"voiced\":2}");
        assertThat(storedText.get("voice-manifest.json")).isEqualTo("{\"voice\":\"Kore\",\"model\":\"" + VOICE_MODEL
                + "\",\"segments\":[{\"index\":1,\"startMs\":500,\"endMs\":3100,\"vi\":\"Xin chào các bạn\"},"
                + "{\"index\":3,\"startMs\":5000,\"endMs\":9800,\"vi\":\"Tạm biệt\"}]}");
    }

    @Test
    void itMayRunLongerThanTheShorteningAndTheVoiceRequestsAtTheirWorst() {
        // Shortening: 2 models x (3 x 60 s + 10 s + 30 s) = 440 s; voicing: 220 s on the voice model plus one
        // 60 s request on the lite voice model = 280 s; plus the 60 s margin.
        assertThat(executor().maxRuntime()).isEqualTo(Duration.ofSeconds(780));
        assertThat(executor().mode()).isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(executor().type()).isEqualTo("tts");
    }
}
