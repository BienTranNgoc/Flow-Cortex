package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.KEY;
import static com.flowcortex.workflow.application.DubFixtures.context;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.application.provider.GeminiClient;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Each provider call is recorded with its model, attempt and latency, while no log line carries the
 * transcript, the translation or the key — whether the calls succeed, are refused or fail.
 */
@ExtendWith(OutputCaptureExtension.class)
class DubTelemetryTest {

    private static final String ZH_1 = "你好朋友们";
    private static final String ZH_2 = "今天我们去吃饭";
    private static final String VI_1 = "Xin chào các bạn";
    private static final String VI_2 = "Hôm nay chúng ta đi ăn";
    private static final String TRANSCRIPT = "{\"segments\":["
            + "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"" + ZH_1 + "\"},"
            + "{\"index\":2,\"startMs\":5000,\"endMs\":9800,\"zh\":\"" + ZH_2 + "\"}]}";
    private static final String BASE = "https://gemini.test/v1beta";

    private final List<Observation.Context> calls = new CopyOnWriteArrayList<>();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DubProperties dub = dub();
    private final GeminiClient gemini = new GeminiClient(builder, dub.gemini(), wait -> { }, recording());
    private final MediaJobs mediaJobs = mock(MediaJobs.class);

    private ObservationRegistry recording() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                calls.add(context);
            }
        });
        return registry;
    }

    private static String candidate(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text.replace("\"", "\\\"") + "\"}]}}]}";
    }

    private static Map<String, String> keyValues(Observation.Context context) {
        return context.getAllKeyValues().stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    @Test
    void providerCallsCarryModelAttemptAndLatencyAndNoTextOrKey(CapturedOutput output) {
        server.expect(requestTo(BASE + "/models/gemini-3.5-flash-lite:generateContent"))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"" + ZH_1 + "\"}}"));
        server.expect(requestTo(BASE + "/models/gemini-3.5-flash-lite:generateContent"))
                .andRespond(withSuccess(candidate("[{\"index\":1,\"vi\":\"" + VI_1 + "\"},{\"index\":2,\"vi\":\""
                        + VI_2 + "\"}]"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/interactions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":429}}"));
        server.expect(requestTo(BASE + "/interactions")).andRespond(withSuccess("{\"steps\":[{\"content\":[{\"data\":\""
                + Base64.getEncoder().encodeToString("RIFF".getBytes()) + "\"}]}]}", MediaType.APPLICATION_JSON));
        when(mediaJobs.putRunObject(any(), anyInt(), anyString(), anyString(), any())).thenAnswer(invocation ->
                new MediaOutput(invocation.getArgument(2), "flowcortex-uploads", "runs/r/steps/7/x",
                        invocation.getArgument(3), 1L, null));

        String translated = new TranslateStepExecutor(gemini, dub)
                .execute(context("translate", 4, TRANSCRIPT, "female"));
        new TtsStepExecutor(gemini, mediaJobs, dub).execute(context("tts", 7, translated, "female"));

        assertThat(output).doesNotContain(ZH_1).doesNotContain(ZH_2).doesNotContain(VI_1).doesNotContain(VI_2)
                .doesNotContain(KEY).doesNotContain("generativelanguage")
                .contains("model=gemini-3.5-flash-lite attempt=1 outcome=server_error status=500")
                .contains("model=gemini-3.5-flash-lite attempt=2 outcome=ok status=200")
                .contains("model=gemini-3.8-flash-tts attempt=1 outcome=quota_exceeded status=429")
                .contains("model=gemini-3.8-flash-lite-tts attempt=1 outcome=ok status=200")
                .contains("latencyMs=");
        assertThat(calls).hasSize(4);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.getName()).isEqualTo("dub.provider.call");
            assertThat(keyValues(call)).containsKeys("model", "attempt", "outcome", "latencyMs");
            assertThat(keyValues(call).values()).noneMatch(value -> value.contains(KEY) || value.contains(VI_1));
        });
        assertThat(keyValues(calls.get(3))).containsEntry("model", "gemini-3.8-flash-lite-tts")
                .containsEntry("attempt", "1").containsEntry("outcome", "ok");
    }

    @Test
    void aFailedStepLogsNeitherTheTextNorTheProvidersMessage(CapturedOutput output) {
        DubProperties oneAttempt = new DubProperties(true, 13, 1.2, dub.voices(), new DubProperties.Gemini(KEY, BASE,
                List.of("gemini-3.5-flash-lite"), List.of("gemini-3.8-flash-tts"), 1, List.of(), Duration.ofSeconds(1)));
        RestClient.Builder single = RestClient.builder();
        MockRestServiceServer stub = MockRestServiceServer.bindTo(single).build();
        stub.expect(requestTo(BASE + "/models/gemini-3.5-flash-lite:generateContent"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"message\":\"bad " + ZH_1 + "\"}}"));
        GeminiClient client = new GeminiClient(single, oneAttempt.gemini(), wait -> { }, ObservationRegistry.NOOP);

        assertThatThrownBy(() -> new TranslateStepExecutor(client, oneAttempt)
                .execute(context("translate", 4, TRANSCRIPT, "female")))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("provider_unavailable");
                    assertThat(e.getMessage()).doesNotContain(ZH_1).doesNotContain(KEY);
                });
        assertThat(output).doesNotContain(ZH_1).doesNotContain(KEY).contains("outcome=refused status=400");
    }
}
