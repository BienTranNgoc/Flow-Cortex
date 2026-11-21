package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.context;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static com.flowcortex.workflow.application.DubFixtures.slots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
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
    private static final String SLOT = "dub-translate";

    private final ObjectMapper json = new ObjectMapper();
    private final LlmSlots llm = slots();

    private static LlmJsonResult answered(String json) {
        return new LlmJsonResult.Answered(json, "gemini-3.5-flash-lite", 10, 10);
    }

    private static LlmJsonResult answer(String... lines) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < lines.length; i++) {
            out.append(i == 0 ? "" : ",").append("{\"index\":").append(i + 1).append(",\"vi\":\"").append(lines[i])
                    .append("\"}");
        }
        return answered(out.append("]").toString());
    }

    private LlmJsonResult ask() {
        return llm.generateJson(eq(SLOT), anyString(), anyString());
    }

    private String run(TranslateStepExecutor executor) {
        return executor.execute(context("translate", 4, TRANSCRIPT, "female"));
    }

    private TranslateStepExecutor executor() {
        return new TranslateStepExecutor(llm, dub());
    }

    @Test
    void everySegmentGetsOneLineAndKeepsItsIndexTimesAndChinese() throws Exception {
        when(ask()).thenReturn(answer(LINE_1, LINE_2));

        JsonNode segments = json.readTree(run(executor())).path("segments");

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).toString()).isEqualTo(
                "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\",\"vi\":\"" + LINE_1 + "\"}");
        assertThat(segments.get(1).toString()).isEqualTo(
                "{\"index\":2,\"startMs\":5000,\"endMs\":9800,\"zh\":\"今天我们去吃饭\",\"vi\":\"" + LINE_2 + "\"}");
        verify(llm, times(1)).generateJson(eq(SLOT), anyString(), anyString());
    }

    @Test
    void thePromptCarriesEachLinesLimitAndTheChinese() {
        when(ask()).thenReturn(answer(LINE_1, LINE_2));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        run(executor());

        verify(llm).generateJson(eq(SLOT), prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("1 (max 33 characters): 你好朋友们")
                .contains("2 (max 62 characters): 今天我们去吃饭");
    }

    @Test
    void aLineAtItsLimitIsAccepted() {
        String limitLine = "a".repeat(33);
        when(ask()).thenReturn(answer(limitLine, LINE_2));

        assertThat(run(executor())).contains(limitLine);
        verify(llm, times(1)).generateJson(eq(SLOT), anyString(), anyString());
    }

    @Test
    void aLineOverItsLimitIsAskedForOnceMoreNamingItsIndex() {
        when(ask()).thenReturn(answer("a".repeat(34), LINE_2), answer(LINE_1, LINE_2));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);

        String output = run(executor());

        verify(llm, times(2)).generateJson(eq(SLOT), prompts.capture(), anyString());
        assertThat(prompts.getAllValues().get(1)).contains("line 1: 34 characters, at most 33")
                .doesNotContain("line 2:");
        assertThat(output).contains(LINE_1).doesNotContain("a".repeat(34));
    }

    @Test
    void theLimitFollowsTheConfiguredRate() {
        when(ask()).thenReturn(answer("a".repeat(27), LINE_2), answer("a".repeat(26), LINE_2));

        String output = run(new TranslateStepExecutor(llm, dub(true, 10)));

        verify(llm, times(2)).generateJson(eq(SLOT), anyString(), anyString());
        assertThat(output).contains("\"vi\":\"" + "a".repeat(26) + "\"");
    }

    @Test
    void aWrongLineCountIsAskedForOnceMoreThenFailsTranslationInvalid() {
        when(ask()).thenReturn(answer(LINE_1), answer(LINE_1));

        assertThatThrownBy(() -> run(executor())).isInstanceOfSatisfying(StepFailedException.class,
                e -> assertThat(e.code()).isEqualTo("translation_invalid"));
        verify(llm, times(2)).generateJson(eq(SLOT), anyString(), anyString());
    }

    @Test
    void anEmptyLineOrAnAnswerThatIsNotTheLineListCountsAsInvalid() {
        when(ask()).thenReturn(answer("", LINE_2), answered("not json"));

        assertThatThrownBy(() -> run(executor())).isInstanceOfSatisfying(StepFailedException.class,
                e -> assertThat(e.code()).isEqualTo("translation_invalid"));
    }

    @Test
    void aTranslationBlockedTwiceFailsTranslationInvalidAfterExactlyTwoAsks() {
        when(ask()).thenReturn(new LlmJsonResult.Blocked("SAFETY"));

        assertThatThrownBy(() -> run(executor())).isInstanceOfSatisfying(StepFailedException.class,
                e -> assertThat(e.code()).isEqualTo("translation_invalid"));
        verify(llm, times(2)).generateJson(eq(SLOT), anyString(), anyString());
    }

    @Test
    void anUnusableAnswerIsTreatedLikeAnyAnswerThatIsNotTheLineList() {
        when(ask()).thenReturn(new LlmJsonResult.Failed(LlmJsonResult.Kind.UNUSABLE), answer(LINE_1, LINE_2));

        assertThat(run(executor())).contains(LINE_1).contains(LINE_2);
        verify(llm, times(2)).generateJson(eq(SLOT), anyString(), anyString());
    }

    @Test
    void aProviderRefusalFailsTheStepWithItsCode() {
        when(ask()).thenReturn(new LlmJsonResult.Failed(LlmJsonResult.Kind.QUOTA_EXCEEDED));

        assertThatThrownBy(() -> run(executor())).isInstanceOfSatisfying(StepFailedException.class,
                e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        verify(llm, times(1)).generateJson(eq(SLOT), anyString(), anyString());
    }

    @Test
    void anOutageFailsTheStepProviderUnavailable() {
        for (LlmJsonResult.Kind kind : new LlmJsonResult.Kind[] {LlmJsonResult.Kind.UPSTREAM,
                LlmJsonResult.Kind.TIMEOUT, LlmJsonResult.Kind.UNREACHABLE, LlmJsonResult.Kind.REJECTED}) {
            when(ask()).thenReturn(new LlmJsonResult.Failed(kind));

            assertThatThrownBy(() -> run(executor())).isInstanceOfSatisfying(StepFailedException.class,
                    e -> assertThat(e.code()).isEqualTo("provider_unavailable"));
        }
    }

    @Test
    void anInputThatIsNotASegmentListFailsContractViolationWithoutAProviderCall() {
        assertThatThrownBy(() -> executor().execute(context("translate", 4, "{\"lines\":[]}", "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
    }

    @Test
    void aSegmentTooShortToHoldACharacterFailsContractViolationNamingItWithoutAProviderCall() {
        // 50 ms at 13 characters a second is a limit of 0: no answer could ever be accepted.
        String input = "{\"segments\":["
                + "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\"},"
                + "{\"index\":2,\"startMs\":5000,\"endMs\":5050,\"zh\":\"好\"}]}";

        assertThatThrownBy(() -> executor().execute(context("translate", 4, input, "female")))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("contract_violation");
                    assertThat(e.getMessage()).contains("Segment 2").contains("too short");
                });
        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
    }

    @Test
    void itRunsOnTheProviderPoolAndWaitsForAThreadAsLongAsTheQueueMayTake() {
        // Pool of 4 with room for 4 waiting: one round of up to 940 s on top of the lease.
        TranslateStepExecutor executor = executor();

        assertThat(executor.pool()).isEqualTo(StepExecutor.Pool.PROVIDER);
        assertThat(executor.queueTimeout(Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(1060));
    }

    @Test
    void duplicateSegmentIndexesFailContractViolationWithoutAProviderCall() {
        String duplicated = "{\"segments\":["
                + "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"你好朋友们\"},"
                + "{\"index\":1,\"startMs\":5000,\"endMs\":9800,\"zh\":\"今天我们去吃饭\"}]}";

        assertThatThrownBy(() -> executor().execute(context("translate", 4, duplicated, "female")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
    }

    @Test
    void aDecomposedLineCountsItsComposedCharactersAndIsKeptComposed() {
        String composed = "ệ".repeat(33);
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        when(ask()).thenReturn(answer(decomposed, LINE_2));

        String output = run(executor());

        verify(llm, times(1)).generateJson(eq(SLOT), anyString(), anyString());
        assertThat(output).contains("\"vi\":\"" + composed + "\"").doesNotContain(decomposed);
    }

    @Test
    void itIsAvailableOnlyWithTheSwitchOnAndAKey() {
        assertThat(new TranslateStepExecutor(slots(true), dub(true, 13)).available()).isTrue();
        assertThat(new TranslateStepExecutor(slots(true), dub(false, 13)).available()).isFalse();
        assertThat(new TranslateStepExecutor(slots(false), dub(true, 13)).available()).isFalse();
    }

    @Test
    void whileUnavailableItFailsDubDisabledWithoutAProviderCall() {
        assertThatThrownBy(() -> run(new TranslateStepExecutor(llm, dub(false, 13))))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("dub_disabled"));
        verify(llm, never()).generateJson(anyString(), anyString(), anyString());
    }

    @Test
    void itMayRunLongerThanBothAsksOnBothModelsAtTheirWorst() {
        // Per ask the slot's worst case is 440 s; two asks = 880 s, plus the 60 s margin.
        assertThat(executor().maxRuntime()).isEqualTo(Duration.ofSeconds(940));
        assertThat(executor().mode()).isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(executor().type()).isEqualTo("translate");
    }
}
