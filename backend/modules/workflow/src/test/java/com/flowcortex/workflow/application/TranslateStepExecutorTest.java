package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.KEY;
import static com.flowcortex.workflow.application.DubFixtures.context;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.application.provider.GeminiClient;
import com.flowcortex.workflow.application.provider.GeminiException;
import java.text.Normalizer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@code translate}: one Vietnamese line per segment, each within its time limit, or a named failure. */
class TranslateStepExecutorTest {

    /** Two segments: 2.6 s (limit 33 characters at 13/s) and 4.8 s (limit 62). */
    private static final String TRANSCRIPT = "{\"segments\":["
            + "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\"},"
            + "{\"index\":2,\"startMs\":5000,\"endMs\":9800,\"zh\":\"今天我们去吃饭\"}]}";
    private static final String LINE_1 = "Xin chào các bạn";
    private static final String LINE_2 = "Hôm nay chúng ta đi ăn";

    private final ObjectMapper json = new ObjectMapper();
    private final GeminiClient gemini = mock(GeminiClient.class);

    private static String answer(String... lines) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < lines.length; i++) {
            out.append(i == 0 ? "" : ",").append("{\"index\":").append(i + 1).append(",\"vi\":\"").append(lines[i])
                    .append("\"}");
        }
        return out.append("]").toString();
    }

    private String run(TranslateStepExecutor executor) {
        return executor.execute(context("translate", 4, TRANSCRIPT, "female"));
    }

    @Test
    void everySegmentGetsOneLineAndKeepsItsIndexTimesAndChinese() throws Exception {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(answer(LINE_1, LINE_2));

        JsonNode segments = json.readTree(run(new TranslateStepExecutor(gemini, dub()))).path("segments");

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).toString()).isEqualTo(
                "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\",\"vi\":\"" + LINE_1 + "\"}");
        assertThat(segments.get(1).toString()).isEqualTo(
                "{\"index\":2,\"startMs\":5000,\"endMs\":9800,\"zh\":\"今天我们去吃饭\",\"vi\":\"" + LINE_2 + "\"}");
        verify(gemini, times(1)).generateJson(anyString(), anyString());
    }

    @Test
    void thePromptCarriesEachLinesLimitAndTheChinese() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(answer(LINE_1, LINE_2));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        run(new TranslateStepExecutor(gemini, dub()));

        verify(gemini).generateJson(prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("1 (max 33 characters): 你好朋友们")
                .contains("2 (max 62 characters): 今天我们去吃饭");
    }

    @Test
    void aLineAtItsLimitIsAccepted() {
        String limitLine = "a".repeat(33);
        when(gemini.generateJson(anyString(), anyString())).thenReturn(answer(limitLine, LINE_2));

        assertThat(run(new TranslateStepExecutor(gemini, dub()))).contains(limitLine);
        verify(gemini, times(1)).generateJson(anyString(), anyString());
    }

    @Test
    void aLineOverItsLimitIsAskedForOnceMoreNamingItsIndex() {
        when(gemini.generateJson(anyString(), anyString()))
                .thenReturn(answer("a".repeat(34), LINE_2), answer(LINE_1, LINE_2));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);

        String output = run(new TranslateStepExecutor(gemini, dub()));

        verify(gemini, times(2)).generateJson(prompts.capture(), anyString());
        assertThat(prompts.getAllValues().get(1)).contains("line 1: 34 characters, at most 33")
                .doesNotContain("line 2:");
        assertThat(output).contains(LINE_1).doesNotContain("a".repeat(34));
    }

    @Test
    void theLimitFollowsTheConfiguredRate() {
        when(gemini.generateJson(anyString(), anyString()))
                .thenReturn(answer("a".repeat(27), LINE_2), answer("a".repeat(26), LINE_2));

        String output = run(new TranslateStepExecutor(gemini, dub(true, KEY, 10)));

        verify(gemini, times(2)).generateJson(anyString(), anyString());
        assertThat(output).contains("\"vi\":\"" + "a".repeat(26) + "\"");
    }

    @Test
    void aWrongLineCountIsAskedForOnceMoreThenFailsTranslationInvalid() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(answer(LINE_1), answer(LINE_1));

        assertThatThrownBy(() -> run(new TranslateStepExecutor(gemini, dub())))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("translation_invalid"));
        verify(gemini, times(2)).generateJson(anyString(), anyString());
    }

    @Test
    void anEmptyLineOrAnAnswerThatIsNotTheLineListCountsAsInvalid() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(answer("", LINE_2), "not json");

        assertThatThrownBy(() -> run(new TranslateStepExecutor(gemini, dub())))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("translation_invalid"));
    }

    @Test
    void aProviderRefusalFailsTheStepWithItsCode() {
        when(gemini.generateJson(anyString(), anyString()))
                .thenThrow(new GeminiException(GeminiException.Reason.QUOTA_EXCEEDED, "gemini-3.8-flash"));

        assertThatThrownBy(() -> run(new TranslateStepExecutor(gemini, dub())))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
    }

    @Test
    void anInputThatIsNotASegmentListFailsContractViolationWithoutAProviderCall() {
        assertThatThrownBy(() -> new TranslateStepExecutor(gemini, dub())
                .execute(context("translate", 4, "{\"lines\":[]}", "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
        verify(gemini, never()).generateJson(anyString(), anyString());
    }

    @Test
    void duplicateSegmentIndexesFailContractViolationWithoutAProviderCall() {
        String duplicated = "{\"segments\":["
                + "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\"},"
                + "{\"index\":1,\"startMs\":5000,\"endMs\":9800,\"zh\":\"今天我们去吃饭\"}]}";

        assertThatThrownBy(() -> new TranslateStepExecutor(gemini, dub())
                .execute(context("translate", 4, duplicated, "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
        verify(gemini, never()).generateJson(anyString(), anyString());
    }

    @Test
    void aDecomposedLineCountsItsComposedCharactersAndIsKeptComposed() {
        String composed = "ệ".repeat(33);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        when(gemini.generateJson(anyString(), anyString())).thenReturn(answer(decomposed, LINE_2));

        String output = run(new TranslateStepExecutor(gemini, dub()));

        verify(gemini, times(1)).generateJson(anyString(), anyString());
        assertThat(output).contains("\"vi\":\"" + composed + "\"").doesNotContain(decomposed);
    }

    @Test
    void itIsAvailableOnlyWithTheSwitchOnAndAKey() {
        assertThat(new TranslateStepExecutor(gemini, dub(true, KEY, 13)).available()).isTrue();
        assertThat(new TranslateStepExecutor(gemini, dub(false, KEY, 13)).available()).isFalse();
        assertThat(new TranslateStepExecutor(gemini, dub(true, "", 13)).available()).isFalse();
        assertThat(new TranslateStepExecutor(gemini, dub(true, null, 13)).available()).isFalse();
    }

    @Test
    void whileUnavailableItFailsDubDisabledWithoutAProviderCall() {
        assertThatThrownBy(() -> run(new TranslateStepExecutor(gemini, dub(false, KEY, 13))))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("dub_disabled"));
        verify(gemini, never()).generateJson(anyString(), anyString());
    }

    @Test
    void itMayRunLongerThanBothAsksOnBothModelsAtTheirWorst() {
        // Per ask: 2 models x (3 x 60 s + 10 s + 30 s) = 440 s; two asks = 880 s.
        assertThat(new TranslateStepExecutor(gemini, dub()).maxRuntime()).isGreaterThan(Duration.ofSeconds(880));
        assertThat(new TranslateStepExecutor(gemini, dub()).mode()).isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(new TranslateStepExecutor(gemini, dub()).type()).isEqualTo("translate");
    }
}
