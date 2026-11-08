package com.flowcortex.workflow.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Requests, retries, fallbacks and refusals of the provider client, against a stubbed provider. */
@ExtendWith(OutputCaptureExtension.class)
class GeminiClientTest {

    private static final String KEY = "fake-key-3c9e";
    private static final String BASE = "https://gemini.test/v1beta";
    private static final String LITE = "gemini-3.5-flash-lite";
    private static final String FLASH = "gemini-3.8-flash";
    private static final String TTS = "gemini-3.8-flash-tts";
    private static final String LITE_TTS = "gemini-3.8-flash-lite-tts";
    private static final String SCHEMA = "{\"type\":\"ARRAY\",\"items\":{\"type\":\"STRING\"}}";
    private static final String QUOTA_BODY = "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded for model "
            + "(limit: 10 requests per day on Free Tier). Please retry in 16h57m32s\",\"status\":\"RESOURCE_EXHAUSTED\"}}";

    private final List<Duration> waits = new ArrayList<>();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    static DubProperties.Gemini config(String baseUrl, List<Duration> retryWaits, Duration callTimeout) {
        return new DubProperties.Gemini(KEY, baseUrl, List.of(LITE, FLASH), List.of(TTS, LITE_TTS),
                retryWaits.size() + 1, retryWaits, callTimeout);
    }

    private GeminiClient client(List<Duration> retryWaits) {
        return new GeminiClient(builder, config(BASE, retryWaits, Duration.ofSeconds(60)), waits::add,
                ObservationRegistry.NOOP);
    }

    private GeminiClient client() {
        return client(List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)));
    }

    private ResponseActions expectTranslate(String model) {
        return server.expect(requestTo(BASE + "/models/" + model + ":generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("translate these"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseSchema.type").value("ARRAY"));
    }

    private ResponseActions expectSpeech(String model) {
        return server.expect(requestTo(BASE + "/interactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(jsonPath("$.model").value(model))
                .andExpect(jsonPath("$.input").value("Xin chào <long pause> Tạm biệt"))
                .andExpect(jsonPath("$.generation_config.speech_config[0].voice").value("Kore"));
    }

    private static String candidate(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quoted(text) + "}]}}]}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String speech(byte[] wav) {
        return "{\"steps\":[{\"type\":\"thought\"},{\"content\":[{\"type\":\"audio\",\"data\":\""
                + Base64.getEncoder().encodeToString(wav) + "\"}]}]}";
    }

    @Test
    void translateSendsThePromptWithTheSchemaAndReturnsTheFirstCandidatesText() {
        expectTranslate(LITE).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        String answer = client().generateJson("translate these", SCHEMA);

        assertThat(answer).isEqualTo("[\"Xin chào\"]");
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void serverErrorsAndTimeoutsOnTheFirstModelMoveTheSameRequestToTheFallbackModel() {
        expectTranslate(LITE).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectTranslate(LITE).andRespond(withException(new SocketTimeoutException("timed out")));
        expectTranslate(LITE).andRespond(withServerError());
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        String answer = client().generateJson("translate these", SCHEMA);

        assertThat(answer).isEqualTo("[\"Xin chào\"]");
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void bothTranslationModelsUnavailableAfterTheirAttemptsIsProviderUnavailable() {
        for (int i = 0; i < 3; i++) {
            expectTranslate(LITE).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        for (int i = 0; i < 3; i++) {
            expectTranslate(FLASH).andRespond(withException(new SocketTimeoutException("timed out")));
        }

        assertThatThrownBy(() -> client().generateJson("translate these", SCHEMA))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_unavailable"));
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void theRetryWaitsAreTheConfiguredOnes() {
        for (int i = 0; i < 3; i++) {
            expectTranslate(LITE).andRespond(withServerError());
        }
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[]"), MediaType.APPLICATION_JSON));

        client(List.of(Duration.ofSeconds(7), Duration.ofSeconds(11))).generateJson("translate these", SCHEMA);

        assertThat(waits).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(11));
    }

    @Test
    void translateQuotaRefusalsRetryWithGrowingWaitsThenTheFallbackModelAnswers() {
        for (int i = 0; i < 3; i++) {
            expectTranslate(LITE).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
        }
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        assertThat(client().generateJson("translate these", SCHEMA)).isEqualTo("[\"Xin chào\"]");
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void translateQuotaRefusalsOnBothModelsAreProviderQuotaExceeded() {
        for (String model : List.of(LITE, FLASH)) {
            for (int i = 0; i < 3; i++) {
                expectTranslate(model).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
            }
        }

        assertThatThrownBy(() -> client().generateJson("translate these", SCHEMA))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        server.verify();
    }

    @Test
    void aQuotaRefusalOnTheMainModelOutranksAnOutageOnTheFallbackModel() {
        for (int i = 0; i < 3; i++) {
            expectTranslate(LITE).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
        }
        for (int i = 0; i < 3; i++) {
            expectTranslate(FLASH).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThatThrownBy(() -> client().generateJson("translate these", SCHEMA))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        server.verify();
    }

    @Test
    void aRefusedTranslateRequestIsNotRetriedAndGoesToTheFallbackModel() {
        expectTranslate(LITE).andRespond(withStatus(HttpStatus.NOT_FOUND));
        expectTranslate(FLASH).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        assertThat(client().generateJson("translate these", SCHEMA)).isEqualTo("[\"Xin chào\"]");
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void speechReturnsTheDecodedWavAndTheModelThatVoicedIt() {
        byte[] wav = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
        expectSpeech(TTS).andRespond(withSuccess(speech(wav), MediaType.APPLICATION_JSON));

        GeminiClient.Speech speech = client().speak("Xin chào <long pause> Tạm biệt", "Kore");

        assertThat(speech.wav()).isEqualTo(wav);
        assertThat(speech.model()).isEqualTo(TTS);
        server.verify();
    }

    @Test
    void aSpeechQuotaRefusalSendsTheSameRequestOnceToTheLiteVoiceModelWithoutWaiting() {
        byte[] wav = "RIFF".getBytes(StandardCharsets.US_ASCII);
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
        expectSpeech(LITE_TTS).andRespond(withSuccess(speech(wav), MediaType.APPLICATION_JSON));

        GeminiClient.Speech speech = client().speak("Xin chào <long pause> Tạm biệt", "Kore");

        assertThat(speech.model()).isEqualTo(LITE_TTS);
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void aSecondSpeechQuotaRefusalIsProviderQuotaExceededAfterExactlyTwoRequests() {
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
        expectSpeech(LITE_TTS).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));

        assertThatThrownBy(() -> client().speak("Xin chào <long pause> Tạm biệt", "Kore"))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void aVoiceModelThatStaysDownHandsTheSameRequestOnceToTheLiteVoiceModelWithoutWaiting() {
        byte[] wav = "RIFF".getBytes(StandardCharsets.US_ASCII);
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectSpeech(TTS).andRespond(withException(new SocketTimeoutException("timed out")));
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectSpeech(LITE_TTS).andRespond(withSuccess(speech(wav), MediaType.APPLICATION_JSON));

        GeminiClient.Speech speech = client().speak("Xin chào <long pause> Tạm biệt", "Kore");

        assertThat(speech.model()).isEqualTo(LITE_TTS);
        assertThat(speech.wav()).isEqualTo(wav);
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void aLiteVoiceModelThatIsDownTooIsProviderUnavailableAfterFourRequests() {
        for (int i = 0; i < 3; i++) {
            expectSpeech(TTS).andRespond(withServerError());
        }
        expectSpeech(LITE_TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client().speak("Xin chào <long pause> Tạm biệt", "Kore"))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_unavailable"));
        assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30));
        server.verify();
    }

    @Test
    void afterAVoiceQuotaRefusalTheLiteVoiceModelGetsOneRequestEvenWhenItIsDown() {
        expectSpeech(TTS).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
        expectSpeech(LITE_TTS).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client().speak("Xin chào <long pause> Tạm biệt", "Kore"))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_quota_exceeded"));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void aVoiceAnswerWithoutAudioFailsAtOnceWithoutAnotherRequest() {
        expectSpeech(TTS).andRespond(withSuccess("{\"steps\":[{\"type\":\"thought\"}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().speak("Xin chào <long pause> Tạm biệt", "Kore"))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.code()).isEqualTo("provider_unavailable"));
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void anInterruptedWaitEscapesWithoutAProviderCode() {
        expectSpeech(TTS).andRespond(withServerError());
        GeminiClient client = new GeminiClient(builder,
                config(BASE, List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)), Duration.ofSeconds(60)),
                wait -> {
                    throw new InterruptedException("shutting down");
                }, ObservationRegistry.NOOP);
        try {
            assertThatThrownBy(() -> client.speak("Xin chào <long pause> Tạm biệt", "Kore"))
                    .isInstanceOf(RuntimeException.class).isNotInstanceOf(GeminiException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        server.verify();
    }

    @Test
    void anInterruptedCallEscapesWithoutAProviderCodeOrAnotherRequest() {
        expectSpeech(TTS).andRespond(request -> {
            Thread.currentThread().interrupt();
            throw new IOException("Request was interrupted");
        });
        try {
            assertThatThrownBy(() -> client().speak("Xin chào <long pause> Tạm biệt", "Kore"))
                    .isInstanceOf(RuntimeException.class).isNotInstanceOf(GeminiException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void theKeyNeverAppearsInWhatTheClientLogs(CapturedOutput output) {
        expectTranslate(LITE).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(QUOTA_BODY));
        expectTranslate(LITE).andRespond(withServerError());
        expectTranslate(LITE).andRespond(withSuccess(candidate("[\"Xin chào\"]"), MediaType.APPLICATION_JSON));

        client().generateJson("translate these", SCHEMA);

        assertThat(output).contains("model=" + LITE).doesNotContain(KEY).doesNotContain("Xin chào");
    }

    @Test
    void aCallThatOutlastsTheConfiguredTimeoutIsGivenUpAndRetried() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(Executors.newCachedThreadPool());
        slow.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(2_000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            String base = "http://127.0.0.1:" + slow.getAddress().getPort() + "/v1beta";
            GeminiClient client = GeminiClient.create(
                    config(base, List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)), Duration.ofMillis(200)),
                    waits::add, ObservationRegistry.NOOP);
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.generateJson("translate these", SCHEMA))
                    .isInstanceOfSatisfying(GeminiException.class,
                            e -> assertThat(e.code()).isEqualTo("provider_unavailable"));

            assertThat(requests.get()).isEqualTo(6);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void anAnswerWhoseBodyStallsAfterTheHeadersIsGivenUpAndRetried() throws IOException {
        AtomicInteger requests = new AtomicInteger();
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
                Thread.sleep(2_000);
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stalling.start();
        try {
            String base = "http://127.0.0.1:" + stalling.getAddress().getPort() + "/v1beta";
            GeminiClient client = GeminiClient.create(
                    config(base, List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)), Duration.ofMillis(200)),
                    waits::add, ObservationRegistry.NOOP);
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.generateJson("translate these", SCHEMA))
                    .isInstanceOfSatisfying(GeminiException.class,
                            e -> assertThat(e.code()).isEqualTo("provider_unavailable"));

            assertThat(requests.get()).isEqualTo(6);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        } finally {
            stalling.stop(0);
        }
    }
}
