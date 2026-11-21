package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.context;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static com.flowcortex.workflow.application.DubFixtures.slots;
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

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmSpeechResult;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code tts}: every reviewed line voiced in one request, stored as one voice file plus its manifest. */
class TtsStepExecutorTest {

    private static final byte[] WAV = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
    private static final String VOICE_MODEL = "gemini-3.8-flash-tts";
    private static final String LITE_VOICE_MODEL = "gemini-3.8-flash-lite-tts";
    private static final String TRANSLATE = "dub-translate";
    private static final String SPEECH = "dub-speech";
    /** 44 characters: 3.4 s at 13/s, over 1.2 x a 2.6 s slot. */
    private static final String LONG_LINE = "Xin chào các bạn hôm nay mình đi chợ sớm nhé";
    /** 40 characters: 3.08 s at 13/s, just under 1.2 x a 2.6 s slot. */
    private static final String FITTING_LINE = "Xin chào các bạn hôm nay mình đi chợ nhé";

    private final LlmSlots llm = slots();
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
        when(llm.speak(eq(SPEECH), anyString(), anyString())).thenReturn(new LlmSpeechResult.Spoken(WAV, VOICE_MODEL));
    }

    private static String segment(int index, long startMs, long endMs, String vi, Boolean edited) {
        return "{\"index\":" + index + ",\"startMs\":" + startMs + ",\"endMs\":" + endMs + ",\"zh\":\"中文" + index
                + "\",\"vi\":\"" + vi + "\"" + (edited == null ? "" : ",\"edited\":" + edited) + "}";
    }

    private static String reviewed(String... segments) {
        return "{\"segments\":[" + String.join(",", segments) + "]}";
    }

    private static LlmJsonResult answered(String json) {
        return new LlmJsonResult.Answered(json, "gemini-3.5-flash-lite", 10, 10);
    }

    private TtsStepExecutor executor() {
        return new TtsStepExecutor(llm, mediaJobs, dub());
    }

    @Test
    void everyNonEmptyLineIsVoicedInOneRequestInSegmentOrderWithTheMappedVoice() {
        StepContext context = context("tts", 7, reviewed(
                segment(1, 500, 3100, "Xin chào", false),
                segment(2, 3500, 4500, "", false),
                segment(3, 5000, 9800, "Tạm biệt", false)), "male");

        String output = executor().execute(context);

        verify(llm, times(1)).speak(SPEECH, "Xin chào <long pause> Tạm biệt", "Charon");
        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
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
        verify(llm).speak(SPEECH, "Xin chào", "Kore");
    }

    @Test
    void uneditedLinesExpectedLongerThanTheirSlotAreShortenedInOneRequestBeforeVoicing() {
        when(llm.generateJson(eq(TRANSLATE), anyString(), anyString())).thenReturn(
                answered("[{\"index\":1,\"vi\":\"Chào cả nhà\"},{\"index\":3,\"vi\":\"Đi chợ sớm\"}]"));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        StepContext context = context("tts", 7, reviewed(
                segment(1, 500, 3100, LONG_LINE, null),
                segment(2, 3500, 9800, "Vừa vặn", false),
                segment(3, 10000, 12600, LONG_LINE, false)), "female");

        executor().execute(context);

        verify(llm, times(1)).generateJson(eq(TRANSLATE), prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("1 (max 33 characters): " + LONG_LINE)
                .contains("3 (max 33 characters): " + LONG_LINE).doesNotContain("Vừa vặn");
        verify(llm).speak(SPEECH, "Chào cả nhà <long pause> Vừa vặn <long pause> Đi chợ sớm", "Kore");
        assertThat(storedText.get("voice-manifest.json")).contains("\"vi\":\"Chào cả nhà\"")
                .contains("\"vi\":\"Đi chợ sớm\"").doesNotContain(LONG_LINE);
    }

    @Test
    void aLineJustUnderTheOverrunThresholdIsVoicedAsWritten() {
        executor().execute(context("tts", 7, reviewed(segment(1, 500, 3100, FITTING_LINE, false)), "female"));

        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
        verify(llm).speak(SPEECH, FITTING_LINE, "Kore");
    }

    @Test
    void aLineTheMemberEditedIsNeverReworded() {
        StepContext context = context("tts", 7, "[" + segment(1, 500, 3100, LONG_LINE, true) + "]", "female");

        executor().execute(context);

        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
        verify(llm).speak(SPEECH, LONG_LINE, "Kore");
        assertThat(storedText.get("voice-manifest.json")).contains("\"vi\":\"" + LONG_LINE + "\"");
    }

    @Test
    void aShorteningAnswerThatIsMissingOrNotShorterKeepsTheMembersLine() {
        when(llm.generateJson(eq(TRANSLATE), anyString(), anyString())).thenReturn(
                answered("[{\"index\":1,\"vi\":\"" + LONG_LINE + " nữa\"}]"));

        executor().execute(context("tts", 7, reviewed(
                segment(1, 500, 3100, LONG_LINE, false),
                segment(2, 10000, 12600, LONG_LINE, false)), "female"));

        verify(llm).speak(SPEECH, LONG_LINE + " <long pause> " + LONG_LINE, "Kore");
    }

    @Test
    void anUnusableOrBlockedShorteningAnswerKeepsTheLinesAsWritten() {
        for (LlmJsonResult unusable : new LlmJsonResult[] {new LlmJsonResult.Failed(LlmJsonResult.Kind.UNUSABLE),
                new LlmJsonResult.Blocked("SAFETY")}) {
            when(llm.generateJson(eq(TRANSLATE), anyString(), anyString())).thenReturn(unusable);

            executor().execute(context("tts", 7, reviewed(segment(1, 500, 3100, LONG_LINE, false)), "female"));
        }

        verify(llm, times(2)).speak(SPEECH, LONG_LINE, "Kore");
    }

    @Test
    void aShorteningOutageFailsTheStepWithTheProvidersCodeBeforeAnyVoiceRequest() {
        when(llm.generateJson(eq(TRANSLATE), anyString(), anyString()))
                .thenReturn(new LlmJsonResult.Failed(LlmJsonResult.Kind.QUOTA_EXCEEDED));

        assertThatThrownBy(() -> executor().execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, LONG_LINE, false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        verify(llm, never()).speak(anyString(), anyString(), anyString());
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
        when(llm.speak(eq(SPEECH), anyString(), anyString()))
                .thenReturn(new LlmSpeechResult.Failed(LlmJsonResult.Kind.QUOTA_EXCEEDED));

        assertThatThrownBy(() -> executor().execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        assertThat(storedText).isEmpty();
    }

    @Test
    void aVoiceAnswerWithoutAudioAfterAQuotaRefusalFailsProviderUnavailable() {
        when(llm.speak(eq(SPEECH), anyString(), anyString()))
                .thenReturn(new LlmSpeechResult.Failed(LlmJsonResult.Kind.UNUSABLE));

        assertThatThrownBy(() -> executor().execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_unavailable"));
        assertThat(storedText).isEmpty();
    }

    @Test
    void withEveryLineEmptiedNoVoiceIsRequestedAndOnlyAnEmptyManifestIsStored() {
        StepContext context = context("tts", 7, reviewed(segment(1, 500, 3100, "", false)), "female");

        String output = executor().execute(context);

        verify(llm, never()).speak(anyString(), anyString(), anyString());
        assertThat(output).isEqualTo("{\"voiced\":0}");
        assertThat(storedText).containsOnlyKeys("voice-manifest.json");
        assertThat(storedText.get("voice-manifest.json"))
                .isEqualTo("{\"voice\":\"Kore\",\"model\":null,\"segments\":[]}");
    }

    @Test
    void whileUnavailableItFailsDubDisabledWithoutAProviderCall() {
        LlmSlots keyless = slots(false);
        TtsStepExecutor off = new TtsStepExecutor(keyless, mediaJobs, dub());

        assertThat(off.available()).isFalse();
        assertThatThrownBy(() -> off.execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("dub_disabled"));
        verify(keyless, never()).speak(anyString(), anyString(), anyString());
        verify(keyless, never()).generateJson(anyString(), anyString(), anyString());
        assertThat(new TtsStepExecutor(llm, mediaJobs, dub(false, 13)).available()).isFalse();
        assertThat(executor().available()).isTrue();
    }

    @Test
    void whenTheVoiceModelStaysDownTheLiteVoiceModelVoicesTheRunAndTheManifestRecordsIt() {
        when(llm.speak(eq(SPEECH), anyString(), anyString()))
                .thenReturn(new LlmSpeechResult.Spoken(WAV, LITE_VOICE_MODEL));

        String output = executor().execute(
                context("tts", 7, reviewed(segment(1, 500, 3100, "Xin chào", false)), "female"));

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
        verify(llm, never()).speak(anyString(), anyString(), anyString());
        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
        assertThat(storedText).isEmpty();
    }

    @Test
    void aDecomposedLineIsMeasuredAndVoicedInItsComposedForm() {
        String decomposed = Normalizer.normalize(FITTING_LINE, Normalizer.Form.NFD);

        executor().execute(context("tts", 7, reviewed(segment(1, 500, 3100, decomposed, false)), "female"));

        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
        verify(llm).speak(SPEECH, FITTING_LINE, "Kore");
        assertThat(storedText.get("voice-manifest.json")).contains("\"vi\":\"" + FITTING_LINE + "\"");
    }

    @Test
    void inlineTagsTheMemberTypedAreLeftOutOfTheVoicedTextAndTheManifest() {
        String output = executor().execute(context("tts", 7, reviewed(
                segment(1, 500, 3100, "Xin chào <long pause> các bạn", false),
                segment(2, 3500, 4500, "<long pause>", false),
                segment(3, 5000, 9800, "<whisper>Tạm biệt", false)), "female"));

        verify(llm).speak(SPEECH, "Xin chào các bạn <long pause> Tạm biệt", "Kore");
        assertThat(output).isEqualTo("{\"voiced\":2}");
        assertThat(storedText.get("voice-manifest.json")).isEqualTo("{\"voice\":\"Kore\",\"model\":\"" + VOICE_MODEL
                + "\",\"segments\":[{\"index\":1,\"startMs\":500,\"endMs\":3100,\"vi\":\"Xin chào các bạn\"},"
                + "{\"index\":3,\"startMs\":5000,\"endMs\":9800,\"vi\":\"Tạm biệt\"}]}");
    }

    @Test
    void itMayRunLongerThanTheShorteningAndTheVoiceRequestsAtTheirWorst() {
        // Shortening 440 s + voicing 280 s + the 60 s margin.
        assertThat(executor().maxRuntime()).isEqualTo(Duration.ofSeconds(780));
        assertThat(executor().mode()).isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(executor().type()).isEqualTo("tts");
    }

    @Test
    void itRunsOnTheProviderPoolAndWaitsForAThreadAsLongAsTheQueueMayTake() {
        // Pool of 4 with room for 4 waiting: one round of up to 780 s on top of the lease.
        assertThat(executor().pool()).isEqualTo(StepExecutor.Pool.PROVIDER);
        assertThat(executor().queueTimeout(Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(900));
    }
}
