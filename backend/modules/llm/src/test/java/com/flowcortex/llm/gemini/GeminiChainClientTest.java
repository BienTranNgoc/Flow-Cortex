package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmJsonResult.Kind;
import com.flowcortex.llm.api.LlmSpeechResult;
import com.flowcortex.llm.gemini.GeminiSlotProperties.Slot;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/** The model chains over HTTP: requests, waits, fallbacks, interrupts and what is logged. */
@ExtendWith(OutputCaptureExtension.class)
class GeminiChainClientTest {

    private static final String KEY = "fake-key-3c9e";
    private static final String BASE = "https://gemini.test/v1beta";
    private static final String LITE = "gemini-3.5-flash-lite";
    private static final String FLASH = "gemini-3.8-flash";
    private static final String TTS = "gemini-3.8-flash-tts";
    private static final String LITE_TTS = "gemini-3.8-flash-lite-tts";
    private static final String TRANSLATE = "dub-translate";
    private static final String SPEECH = "dub-speech";
    private static final String SCHEMA = "{\"type\":\"ARRAY\",\"items\":{\"type\":\"STRING\"}}";
    private static final String TEXT = "Xin chào <long pause> Tạm biệt";
    private static final String QUOTA_BODY = "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded for model "
            + "(limit: 10 requests per day on Free Tier). Please retry in 16h57m32s\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
    private static final List<Duration> WAITS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30));

    private final List<Duration> waits = new ArrayList<>();
    private final List<Observation.Context> attempts = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private static GeminiProperties properties(String key, String base) {
        return new GeminiProperties(key, URI.create(base), Duration.ofSeconds(5), Duration.ofSeconds(20),
                Duration.ofSeconds(20));
    }

    private static GeminiSlotProperties slots(List<Duration> retryWaits, Duration callTimeout) {
        int attempts = retryWaits.size() + 1;
        return new GeminiSlotProperties(Map.of(
                TRANSLATE, new Slot(List.of(LITE, FLASH), attempts, retryWaits, callTimeout),
                SPEECH, new Slot(List.of(TTS, LITE_TTS), attempts, retryWaits, callTimeout)));
    }

    private ObservationRegistry recording() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                attempts.add(context);
            }
        });
        return registry;
    }

    private GeminiChainClient client(List<Duration> retryWaits) {
        return new GeminiChainClient(properties(KEY, BASE), slots(retryWaits, Duration.ofSeconds(60)),
                timeout -> builder.build(), waits::add, new GeminiTelemetry(meters, recording()));
    }

    private GeminiChainClient client() {
        return client(WAITS);
    }

    private GeminiChainClient overRealHttp(String base, Duration callTimeout) {
        return new GeminiChainClient(properties(KEY, base), slots(WAITS, callTimeout),
                timeout -> GeminiAttempts.restClient(HttpClient.newBuilder().build(), timeout), waits::add,
                new GeminiTelemetry(meters, recording()));
    }

    /** Each attempt the client made, in order, as {@code model#attempt:outcome}. */
    private List<String> attemptOutcomes() {
        return attempts.stream()
                .map(call -> keyValues(call).get("model") + "#" + keyValues(call).get("attempt") + ":"
                        + keyValues(call).get("outcome"))
                .toList();
    }

    private ResponseActions expectTranslate(String model) {
        return server.expect(requestTo(BASE + "/models/" + model + ":generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("translate these"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseSchema.type").value("ARRAY"))
                .andExpect(jsonPath("$.generationConfig.temperature").doesNotExist())
                .andExpect(jsonPath("$.generationConfig.maxOutputTokens").doesNotExist());
    }

    private ResponseActions expectSpeech(String model) {
        return server.expect(requestTo(BASE + "/interactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(jsonPath("$.model").value(model))
                .andExpect(jsonPath("$.input").value(TEXT))
                .andExpect(jsonPath("$.generation_config.speech_config[0].voice").value("Kore"));
    }

    private static String candidate(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quoted(text) + "}]},\"finishReason\":\"STOP\"}]}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String speech(byte[] wav) {
        return "{\"steps\":[{\"type\":\"thought\"},{\"content\":[{\"type\":\"audio\",\"data\":\""
                + Base64.getEncoder().encodeToString(wav) + "\"}]}]}";
    }

    private static ResponseActions quota(ResponseActions expectation) {
        expectation.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body(QUOTA_BODY));
        return expectation;
    }

    private static Map<String, String> keyValues(Observation.Context context) {
        return context.getAllKeyValues().stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    @Test
    void translateSendsThePromptWithTheSchemaAndNoSamplingSettingsAndReturnsTheAnswer() {
        expectTranslate(LITE).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        LlmJsonResult result = client().generateJson(TRANSLATE, "translate these", SCHEMA);

        assertThat(result).isEqualTo(new LlmJsonResult.Answered("[\"Xin chào\"]", LITE, 0, 0));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void anAnswerWithoutAFinishReasonIsStillTheAnswer() {
        expectTranslate(LITE).andRespond(withSuccess(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[\\\"Xin chào\\\"]\"}]}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isEqualTo(new LlmJsonResult.Answered("[\"Xin chào\"]", LITE, 0, 0));
        server.verify();
    }

    @Test
    void serverErrorsMoveTheRequestToTheFallbackModelAfterTheConfiguredWaits() {
        expectTranslate(LITE).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectTranslate(LITE).andRespond(withException(new SocketTimeoutException("timed out")));
        expectTranslate(LITE).andRespond(withServerError());
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        LlmJsonResult result = client().generateJson(TRANSLATE, "translate these", SCHEMA);

        assertThat(result).isEqualTo(new LlmJsonResult.Answered("[\"Xin chào\"]", FLASH, 0, 0));
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void theRetryWaitsAreTheConfiguredOnes() {
        for (int i = 0; i < 3; i++) {
            expectTranslate(LITE).andRespond(withServerError());
        }
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[]"), MediaType.APPLICATION_JSON));

        client(List.of(Duration.ofSeconds(7), Duration.ofSeconds(11))).generateJson(TRANSLATE, "translate these",
                SCHEMA);

        assertThat(waits).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(11));
    }

    @Test
    void quotaOnTheMainModelOutranksAnOutageOnTheFallbackModel() {
        for (int i = 0; i < 3; i++) {
            quota(expectTranslate(LITE));
        }
        for (int i = 0; i < 3; i++) {
            expectTranslate(FLASH).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isEqualTo(new LlmJsonResult.Failed(Kind.QUOTA_EXCEEDED));
        server.verify();
    }

    @Test
    void quotaRefusalsAreRetriedWithTheWaitsThenTheFallbackModelAnswers() {
        for (int i = 0; i < 3; i++) {
            quota(expectTranslate(LITE));
        }
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[]"), MediaType.APPLICATION_JSON));

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isInstanceOf(LlmJsonResult.Answered.class);
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void aRejectedRequestIsNotRetriedAndGoesToTheFallbackModelAtOnce() {
        expectTranslate(LITE).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[]"), MediaType.APPLICATION_JSON));

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isInstanceOf(LlmJsonResult.Answered.class);
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void anAnswerThatIsNotJsonEndsTheCallAfterOneRequest() {
        expectTranslate(LITE).andRespond(withSuccess(candidate("not json at all"), MediaType.APPLICATION_JSON));

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isEqualTo(new LlmJsonResult.Failed(Kind.UNUSABLE));
        server.verify();
    }

    @Test
    void aBodyThatIsNotJsonEndsTheCallAfterOneRequest() {
        expectTranslate(LITE).andRespond(withSuccess("<html>gateway page</html>", MediaType.APPLICATION_JSON));

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isEqualTo(new LlmJsonResult.Failed(Kind.UNUSABLE));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void aSafetyBlockEndsTheCallAfterOneRequest() {
        expectTranslate(LITE).andRespond(withSuccess("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client().generateJson(TRANSLATE, "translate these", SCHEMA))
                .isEqualTo(new LlmJsonResult.Blocked("SAFETY"));
        server.verify();
    }

    @Test
    void speechReturnsTheDecodedWavAndTheModelThatVoicedIt() {
        byte[] wav = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
        expectSpeech(TTS).andRespond(withSuccess(speech(wav), MediaType.APPLICATION_JSON));

        LlmSpeechResult result = client().speak(SPEECH, TEXT, "Kore");

        assertThat(result).isInstanceOfSatisfying(LlmSpeechResult.Spoken.class, spoken -> {
            assertThat(spoken.wav()).isEqualTo(wav);
            assertThat(spoken.model()).isEqualTo(TTS);
        });
        server.verify();
    }

    @Test
    void quotaOnTheMainVoiceModelSendsTheSameRequestOnceToTheLiteModelWithoutWaiting() {
        byte[] wav = "RIFF".getBytes(StandardCharsets.US_ASCII);
        quota(expectSpeech(TTS));
        expectSpeech(LITE_TTS).andRespond(withSuccess(speech(wav), MediaType.APPLICATION_JSON));

        LlmSpeechResult result = client().speak(SPEECH, TEXT, "Kore");

        assertThat(result).isInstanceOfSatisfying(LlmSpeechResult.Spoken.class,
                spoken -> assertThat(spoken.model()).isEqualTo(LITE_TTS));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void aVoiceModelThatStaysDownHandsTheRequestOnceToTheLiteModelAfterTheWaits() {
        byte[] wav = "RIFF".getBytes(StandardCharsets.US_ASCII);
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectSpeech(TTS).andRespond(withException(new SocketTimeoutException("timed out")));
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectSpeech(LITE_TTS).andRespond(withSuccess(speech(wav), MediaType.APPLICATION_JSON));

        LlmSpeechResult result = client().speak(SPEECH, TEXT, "Kore");

        assertThat(result).isInstanceOfSatisfying(LlmSpeechResult.Spoken.class,
                spoken -> assertThat(spoken.model()).isEqualTo(LITE_TTS));
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void aLiteVoiceModelThatIsDownTooFailsWithTheLastKindAfterFourRequests() {
        for (int i = 0; i < 3; i++) {
            expectSpeech(TTS).andRespond(withServerError());
        }
        expectSpeech(LITE_TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client().speak(SPEECH, TEXT, "Kore")).isEqualTo(new LlmSpeechResult.Failed(Kind.UPSTREAM));
        server.verify();
    }

    @Test
    void aVoiceAnswerWithoutAudioFailsAtOnceWithoutAnotherRequest() {
        expectSpeech(TTS).andRespond(withSuccess("{\"steps\":[{\"type\":\"thought\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client().speak(SPEECH, TEXT, "Kore")).isEqualTo(new LlmSpeechResult.Failed(Kind.UNUSABLE));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void anAnswerWithoutAudioOnTheLiteModelAfterAQuotaRefusalIsUnusableNotQuota() {
        quota(expectSpeech(TTS));
        expectSpeech(LITE_TTS).andRespond(withSuccess("{\"steps\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client().speak(SPEECH, TEXT, "Kore")).isEqualTo(new LlmSpeechResult.Failed(Kind.UNUSABLE));
        server.verify();
    }

    @Test
    void aRejectionOnTheLiteModelAfterAQuotaRefusalIsQuota() {
        quota(expectSpeech(TTS));
        expectSpeech(LITE_TTS).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(client().speak(SPEECH, TEXT, "Kore")).isEqualTo(new LlmSpeechResult.Failed(Kind.QUOTA_EXCEEDED));
        server.verify();
    }

    @Test
    void anInterruptedWaitEscapesWithoutAProviderResult() {
        expectSpeech(TTS).andRespond(withServerError());
        GeminiChainClient interrupted = new GeminiChainClient(properties(KEY, BASE),
                slots(WAITS, Duration.ofSeconds(60)), timeout -> builder.build(), wait -> {
                    throw new InterruptedException("shutting down");
                }, new GeminiTelemetry(meters, ObservationRegistry.NOOP));
        try {
            assertThatThrownBy(() -> interrupted.speak(SPEECH, TEXT, "Kore")).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }

    @Test
    void anInterruptedCallEscapesWithoutAProviderResultOrAnotherRequest() {
        expectSpeech(TTS).andRespond(request -> {
            Thread.currentThread().interrupt();
            throw new IOException("Request was interrupted");
        });
        try {
            assertThatThrownBy(() -> client().speak(SPEECH, TEXT, "Kore")).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void anInterruptedLastAttemptOnAModelWithAFallbackSendsNothingToTheFallback() {
        expectTranslate(LITE).andRespond(withServerError());
        expectTranslate(LITE).andRespond(withServerError());
        expectTranslate(LITE).andRespond(request -> {
            Thread.currentThread().interrupt();
            throw new IOException("Request was interrupted");
        });
        try {
            assertThatThrownBy(() -> client().generateJson(TRANSLATE, "translate these", SCHEMA))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }

    @Test
    void theKeyAndTheTextNeverAppearInWhatTheClientLogs(CapturedOutput output) {
        quota(expectTranslate(LITE));
        expectTranslate(LITE).andRespond(withServerError().body("{\"error\":{\"message\":\"echo translate these\"}}"));
        expectTranslate(LITE).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        client().generateJson(TRANSLATE, "translate these", SCHEMA);

        assertThat(output).contains("model=" + LITE).doesNotContain(KEY).doesNotContain("Xin chào")
                .doesNotContain("translate these").doesNotContain("Rate limit");
    }

    @Test
    void providerCallsCarryModelAttemptAndLatencyAndNoTextOrKey(CapturedOutput output) {
        expectTranslate(LITE).andRespond(withServerError().body("{\"error\":{\"message\":\"leak-in-error-body\"}}"));
        expectTranslate(LITE).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));
        quota(expectSpeech(TTS));
        expectSpeech(LITE_TTS).andRespond(withSuccess(speech("RIFF".getBytes(StandardCharsets.US_ASCII)),
                MediaType.APPLICATION_JSON));
        GeminiChainClient client = client();

        client.generateJson(TRANSLATE, "translate these", SCHEMA);
        client.speak(SPEECH, TEXT, "Kore");

        assertThat(output).doesNotContain(KEY).doesNotContain("leak-in-error-body").doesNotContain("Xin chào")
                .doesNotContain(TEXT)
                .contains("outcome=upstream status=500 model=" + LITE + " slot=" + TRANSLATE + " attempt=1")
                .contains("outcome=answered status=200 model=" + LITE + " slot=" + TRANSLATE + " attempt=2")
                .contains("outcome=quota_exceeded status=429 model=" + TTS + " slot=" + SPEECH + " attempt=1")
                .contains("outcome=answered status=200 model=" + LITE_TTS + " slot=" + SPEECH + " attempt=1")
                .contains("latency_ms=");
        assertThat(attempts).hasSize(4);
        assertThat(attempts).allSatisfy(call -> {
            assertThat(call.getName()).isEqualTo("llm.attempt");
            assertThat(keyValues(call)).containsKeys("slot", "model", "attempt", "outcome");
            assertThat(keyValues(call).values()).noneMatch(value -> value.contains(KEY) || value.contains("Xin chào"));
        });
        assertThat(keyValues(attempts.get(3))).containsEntry("slot", SPEECH).containsEntry("model", LITE_TTS)
                .containsEntry("attempt", "1").containsEntry("outcome", "answered");
        io.micrometer.core.instrument.Timer timer = meters.get("llm.requests").tag("model", LITE)
                .tag("outcome", "upstream").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags().stream().map(tag -> tag.getKey()).collect(Collectors.toSet()))
                .isEqualTo(Set.of("model", "outcome"));
    }

    @Test
    void aBlankKeyFailsRejectedWithoutCallingGemini() {
        GeminiChainClient unconfigured = new GeminiChainClient(properties(" ", BASE),
                slots(WAITS, Duration.ofSeconds(60)), timeout -> builder.build(), waits::add,
                new GeminiTelemetry(meters, ObservationRegistry.NOOP));

        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.generateJson(TRANSLATE, "translate these", SCHEMA))
                .isEqualTo(new LlmJsonResult.Failed(Kind.REJECTED));
        assertThat(unconfigured.speak(SPEECH, TEXT, "Kore")).isEqualTo(new LlmSpeechResult.Failed(Kind.REJECTED));
        server.verify();
    }

    @Test
    void anUnknownSlotIsAnIllegalArgumentAndTheWorstCasesComeFromTheSlot() {
        GeminiChainClient client = client();

        assertThat(client.configured()).isTrue();
        assertThat(client.jsonWorstCase(TRANSLATE)).isEqualTo(Duration.ofSeconds(440));
        assertThat(client.speechWorstCase(SPEECH)).isEqualTo(Duration.ofSeconds(280));
        assertThatIllegalArgumentException().isThrownBy(() -> client.generateJson("nope", "x", SCHEMA))
                .withMessageContaining("nope");
        assertThatIllegalArgumentException().isThrownBy(() -> client.speak("nope", "x", "Kore"));
        assertThatIllegalArgumentException().isThrownBy(() -> client.jsonWorstCase("nope"));
    }

    @Test
    void aCallThatOutlastsTheConfiguredTimeoutIsGivenUpAndRetried() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(Executors.newCachedThreadPool());
        slow.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            GeminiChainClient client = overRealHttp("http://127.0.0.1:" + slow.getAddress().getPort() + "/v1beta",
                    Duration.ofMillis(200));
            long started = System.nanoTime();

            assertThat(client.generateJson(TRANSLATE, "translate these", SCHEMA))
                    .isEqualTo(new LlmJsonResult.Failed(Kind.TIMEOUT));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(attemptOutcomes()).containsExactly(LITE + "#1:timeout", LITE + "#2:timeout",
                    LITE + "#3:timeout", FLASH + "#1:timeout", FLASH + "#2:timeout", FLASH + "#3:timeout");
            assertThat(requests.get()).isLessThanOrEqualTo(6);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(6));
        } finally {
            release.countDown();
            slow.stop(0);
        }
    }

    @Test
    void anAnswerWhoseBodyStallsAfterTheHeadersIsGivenUpAndRetried() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        byte[] part = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[".getBytes(StandardCharsets.UTF_8);
        HttpServer stalling = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalling.setExecutor(Executors.newCachedThreadPool());
        stalling.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 1_000);
                exchange.getResponseBody().write(part);
                exchange.getResponseBody().flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stalling.start();
        try {
            GeminiChainClient client = overRealHttp("http://127.0.0.1:" + stalling.getAddress().getPort() + "/v1beta",
                    Duration.ofMillis(200));
            long started = System.nanoTime();

            assertThat(client.generateJson(TRANSLATE, "translate these", SCHEMA)).isInstanceOfSatisfying(
                    LlmJsonResult.Failed.class,
                    failed -> assertThat(failed.kind()).isIn(Kind.TIMEOUT, Kind.UPSTREAM));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(attemptOutcomes()).hasSize(6)
                    .allSatisfy(outcome -> assertThat(outcome).matches(".*:(timeout|upstream)"));
            assertThat(requests.get()).isLessThanOrEqualTo(6);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(6));
        } finally {
            release.countDown();
            stalling.stop(0);
        }
    }
}
