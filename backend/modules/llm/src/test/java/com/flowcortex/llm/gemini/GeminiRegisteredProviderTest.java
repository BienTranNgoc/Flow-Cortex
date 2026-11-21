package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.gemini.GeminiSlotProperties.Slot;
import com.flowcortex.llm.registry.ProbeResult;
import com.flowcortex.llm.registry.RegisteredProvider.ConfigStatus;
import com.flowcortex.llm.registry.RegisteredProvider.SlotView;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModel;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModels;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The Gemini registry provider against a local stand-in for the API, over real HTTP. */
@ExtendWith(OutputCaptureExtension.class)
class GeminiRegisteredProviderTest {

    private static final String KEY = "fake-key-7d21";
    private static final String MODELS_BODY = "{\"models\":[{\"name\":\"models/gemini-3.8-flash\","
            + "\"displayName\":\"Gemini 3.8 Flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}";
    private static final String ANSWER = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]},"
            + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":4}}";
    private static final String TRUNCATED = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\"}]},"
            + "\"finishReason\":\"MAX_TOKENS\"}]}";

    private record Seen(String method, String path, String query, String key, String body) {
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int status = 200;
    private String responseBody = MODELS_BODY;
    private long delayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readAllBytes();
        seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                new String(request, StandardCharsets.UTF_8)));
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private GeminiRegisteredProvider provider(String key) {
        return provider(key, Duration.ofSeconds(5));
    }

    private GeminiRegisteredProvider provider(String key, Duration readTimeout) {
        GeminiProperties properties = new GeminiProperties(key,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"), Duration.ofSeconds(2),
                readTimeout, Duration.ofSeconds(5));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1).build();
        var rest = GeminiAttempts.restClient(http, readTimeout);
        LlmClient client = new GeminiLlmClient(properties, rest, new SimpleMeterRegistry());
        Slot slot = new Slot(List.of("gemini-3.5-flash-lite", "gemini-3.8-flash"), 1, List.of(),
                Duration.ofSeconds(5));
        Slot speech = new Slot(List.of("gemini-3.8-flash-tts"), 1, List.of(), Duration.ofSeconds(5));
        return new GeminiRegisteredProvider(properties,
                new GeminiSlotProperties(Map.of("dub-translate", slot, "dub-speech", speech)), rest, client);
    }

    @Test
    void theListingRequestIsAGetWithTheKeyInTheHeaderOnly() {
        VendorModels models = provider(KEY).listModels();

        assertThat(models.failure()).isNull();
        assertThat(models.models()).containsExactly(new VendorModel("gemini-3.8-flash", "Gemini 3.8 Flash"));
        assertThat(seen).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/v1beta/models");
            assertThat(request.query()).isEqualTo("pageSize=1000");
            assertThat(request.key()).isEqualTo(KEY);
            assertThat(request.body()).isEmpty();
            assertThat(request.query()).doesNotContain(KEY);
        });
    }

    @Test
    void theLiveCheckAsksForOneModelAndNeverGenerates() {
        ProbeResult result = provider(KEY).liveCheck();

        assertThat(result.ok()).isTrue();
        assertThat(result.tested()).isTrue();
        assertThat(result.code()).isNull();
        assertThat(seen).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.query()).isEqualTo("pageSize=1");
            assertThat(request.path()).doesNotContain("generateContent");
        });
    }

    @Test
    void aRefusedKeyIsRejected() {
        status = 403;
        responseBody = "{\"error\":{\"message\":\"API key not valid " + KEY + "\"}}";

        ProbeResult result = provider(KEY).liveCheck();

        assertThat(result.ok()).isFalse();
        assertThat(result.tested()).isTrue();
        assertThat(result.code()).isEqualTo("rejected");
    }

    @Test
    void aQuotaAnswerIsQuotaExceeded() {
        status = 429;

        assertThat(provider(KEY).liveCheck().code()).isEqualTo("quota_exceeded");
        assertThat(provider(KEY).listModels().failure()).isEqualTo("quota_exceeded");
    }

    @Test
    void aServerThatNeverAnswersTimesOutAfterTheReadTimeout() {
        delayMillis = 3_000;

        long started = System.nanoTime();
        ProbeResult result = provider(KEY, Duration.ofMillis(300)).liveCheck();

        assertThat(result.code()).isEqualTo("timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aBlankKeyIsNotConfiguredAndSendsNothing() {
        GeminiRegisteredProvider blank = provider(" ");

        assertThat(blank.configStatus()).isEqualTo(new ConfigStatus(false, List.of("GEMINI_API_KEY")));
        assertThat(blank.listModels()).isEqualTo(new VendorModels(List.of(), "missing_config"));
        assertThat(blank.liveCheck().code()).isEqualTo("missing_config");
        assertThat(blank.testModel("gemini-3.8-flash").missingSettings()).containsExactly("GEMINI_API_KEY");
        assertThat(seen).isEmpty();
    }

    @Test
    void aSetKeyIsConfigured() {
        assertThat(provider(KEY).configStatus()).isEqualTo(new ConfigStatus(true, List.of()));
        assertThat(provider(KEY).key()).isEqualTo("gemini");
        assertThat(provider(KEY).liveCheckable()).isTrue();
    }

    @Test
    void anUnreadableListingIsUpstream() {
        responseBody = "<html>";

        assertThat(provider(KEY).listModels().failure()).isEqualTo("upstream");
    }

    @Test
    void acceptsGeminiTextModelIds() {
        assertThat(provider(KEY).acceptsModelKey("gemini-3.8-flash")).isTrue();
        assertThat(provider(KEY).acceptsModelKey("gemini-3.5-flash-lite")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5", "Gemini-3", "gemini-3.8-flash-tts", "gemini-2.5-flash-image",
            "gemini-live-2.5-flash", "gemini-embedding-001", "gemini-3.8-flash-native-audio", "gemini 3", "",
            "gemini-3/../x"})
    void refusesIdsThatAreNotGeminiTextModels(String id) {
        assertThat(provider(KEY).acceptsModelKey(id)).isFalse();
    }

    @Test
    void refusesAnIdOfMoreThan128Characters() {
        assertThat(provider(KEY).acceptsModelKey("gemini-" + "a".repeat(122))).isFalse();
        assertThat(provider(KEY).acceptsModelKey("gemini-" + "a".repeat(121))).isTrue();
        assertThat(provider(KEY).acceptsModelKey(null)).isFalse();
    }

    @Test
    void testModelSendsOneGenerateContentWithAnOutputCap() {
        responseBody = ANSWER;

        ProbeResult result = provider(KEY).testModel("gemini-3.8-flash");

        assertThat(result.ok()).isTrue();
        assertThat(result.outputTokens()).isEqualTo(4);
        assertThat(seen).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3.8-flash:generateContent");
            assertThat(request.key()).isEqualTo(KEY);
            assertThat(request.body()).contains("\"maxOutputTokens\":256");
        });
    }

    @Test
    void aTruncatedAnswerIsUnusable() {
        responseBody = TRUNCATED;

        ProbeResult result = provider(KEY).testModel("gemini-3.8-flash");

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("unusable");
    }

    @Test
    void slotsListTheConfiguredChainsByNameWithModelsInOrder() {
        assertThat(provider(KEY).slots()).containsExactly(
                new SlotView("dub-speech", List.of("gemini-3.8-flash-tts")),
                new SlotView("dub-translate", List.of("gemini-3.5-flash-lite", "gemini-3.8-flash")));
    }

    @Test
    void logsOneLineWithoutTheKeyOrTheErrorBody(CapturedOutput output) {
        status = 403;
        responseBody = "{\"error\":{\"message\":\"secret-detail " + KEY + "\"}}";

        provider(KEY).liveCheck();

        assertThat(output.getAll()).contains("llm.registry provider=gemini", "outcome=rejected", "latency_ms=");
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain("secret-detail");
    }
}
