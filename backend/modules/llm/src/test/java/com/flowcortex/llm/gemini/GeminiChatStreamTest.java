package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.flowcortex.llm.api.LlmChatListener;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmTool;
import com.flowcortex.llm.api.LlmTurn;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiChatStreamTest {

    private static final String KEY = "test-only-gemini-key";
    private static final String MODEL = "gemini-3.5-flash";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestKeys = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private Handler handler;

    @FunctionalInterface
    private interface Handler {
        void handle(int call, OutputStream out) throws IOException, InterruptedException;
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", this::serve);
        server.start();
    }

    @AfterEach
    void stopStub() {
        release.countDown();
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void serve(HttpExchange exchange) throws IOException {
        int call = requestBodies.size();
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestPaths.add(exchange.getRequestURI().toString());
        requestKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-goog-api-key")));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            handler.handle(call, out);
        } catch (IOException | InterruptedException ex) {
            // The client closing the connection mid-stream is what the cancel tests provoke.
        }
    }

    private static void event(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String textChunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    private static String lastChunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text
                + "\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":12,"
                + "\"candidatesTokenCount\":7},\"modelVersion\":\"gemini-3.5-flash-001\"}";
    }

    private GeminiChatClient client(String apiKey, Duration readTimeout) {
        GeminiProperties properties = new GeminiProperties(apiKey,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"), Duration.ofSeconds(2),
                readTimeout, Duration.ofSeconds(20));
        return new GeminiChatClient(properties, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout()).build(), meters);
    }

    private GeminiChatClient client() {
        return client(KEY, Duration.ofSeconds(10));
    }

    private static LlmChatRequest request(String language, List<LlmTurn> turns) {
        return new LlmChatRequest(MODEL, "You are Nodi.", turns, List.of(new LlmTool("list_workflows",
                "Lists the workflows.", "{\"type\":\"OBJECT\",\"properties\":{}}")), 0.4, 1024, language);
    }

    private static LlmChatRequest request() {
        return request("en", List.of(new LlmTurn.User("Hello")));
    }

    private static final class Recorder implements LlmChatListener {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean cancel = new AtomicBoolean();
        boolean cancelAfterFirstText;

        @Override
        public void onText(String chunk) {
            texts.add(chunk);
            if (cancelAfterFirstText) {
                requestCancel();
            }
        }

        @Override
        public void onToolCall(String name, String argsJson, String providerState) {
            calls.add(List.of(name, argsJson, String.valueOf(providerState)));
        }

        @Override
        public boolean cancelled() {
            return cancel.get();
        }

        void requestCancel() {
            cancel.set(true);
        }
    }

    @Test
    void threeTextChunksReachTheListenerInOrderAndTheOutcomeCarriesModelAndTokens() {
        handler = (call, out) -> {
            event(out, textChunk("Xin "));
            event(out, textChunk("chào "));
            event(out, lastChunk("bạn"));
        };
        Recorder listener = new Recorder();

        LlmChatOutcome outcome = client().stream(request(), listener);

        assertThat(listener.texts).containsExactly("Xin ", "chào ", "bạn");
        assertThat(outcome).isEqualTo(new LlmChatOutcome.Finished("gemini-3.5-flash-001", 12, 7, List.of()));
        assertThat(requestKeys).containsExactly(KEY);
        assertThat(requestPaths.get(0)).isEqualTo("/v1beta/models/" + MODEL + ":streamGenerateContent?alt=sse");
        assertThat(meters.get("llm.requests").tag("outcome", "answered").timer().count()).isEqualTo(1);
    }

    @Test
    void theBodyCarriesInstructionLanguageLineToolsAndNoJsonMimeType() throws Exception {
        handler = (call, out) -> event(out, lastChunk("ok"));

        client().stream(request("vi", List.of(new LlmTurn.User("Hi"), new LlmTurn.Model("Hello"),
                new LlmTurn.User("Again"))), new Recorder());

        JsonNode body = JSON.readTree(requestBodies.get(0));
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString())
                .isEqualTo("You are Nodi.");
        assertThat(body.path("systemInstruction").path("parts").path(1).path("text").asString())
                .isEqualTo("Answer in Vietnamese.");
        assertThat(body.path("contents")).hasSize(3);
        assertThat(body.path("contents").path(1).path("role").asString()).isEqualTo("model");
        assertThat(body.path("tools").path(0).path("functionDeclarations").path(0).path("name").asString())
                .isEqualTo("list_workflows");
        assertThat(body.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.4);
        assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(1024);
        assertThat(body.path("generationConfig").has("responseMimeType")).isFalse();
    }

    @Test
    void anUnsupportedLanguageAnswersInEnglish() throws Exception {
        handler = (call, out) -> event(out, lastChunk("ok"));

        client().stream(request("fr", List.of(new LlmTurn.User("Salut"))), new Recorder());

        JsonNode parts = JSON.readTree(requestBodies.get(0)).path("systemInstruction").path("parts");
        assertThat(parts.path(1).path("text").asString()).isEqualTo("Answer in English.");
    }

    @Test
    void aFunctionCallPartReachesTheListenerWithItsSignatureAndTheOutcome() {
        handler = (call, out) -> event(out, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
                + "{\"functionCall\":{\"name\":\"list_workflows\",\"args\":{\"q\":\"video\"}},"
                + "\"thoughtSignature\":\"sig-A\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2}}");
        Recorder listener = new Recorder();

        LlmChatOutcome outcome = client().stream(request(), listener);

        assertThat(listener.calls).containsExactly(List.of("list_workflows", "{\"q\":\"video\"}", "sig-A"));
        assertThat(outcome).isEqualTo(new LlmChatOutcome.Finished(MODEL, 5, 2,
                List.of(new LlmChatOutcome.ToolCall("list_workflows", "{\"q\":\"video\"}", "sig-A"))));
    }

    @Test
    void aSafetyFinishIsBlocked() {
        handler = (call, out) -> event(out,
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");

        assertThat(client().stream(request(), new Recorder())).isEqualTo(new LlmChatOutcome.Blocked("SAFETY"));
        assertThat(meters.get("llm.requests").tag("outcome", "blocked").timer().count()).isEqualTo(1);
    }

    @Test
    void aPromptBlockReasonIsBlocked() {
        handler = (call, out) -> event(out, "{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}");

        assertThat(client().stream(request(), new Recorder()))
                .isEqualTo(new LlmChatOutcome.Blocked("PROHIBITED_CONTENT"));
    }

    @Test
    void aMaxTokensFinishIsATruncatedAnswerCarryingItsTextAndTokens() {
        handler = (call, out) -> event(out,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"cut\"}]},\"finishReason\":\"MAX_TOKENS\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":2048}}");
        Recorder listener = new Recorder();

        assertThat(client().stream(request(), listener)).isEqualTo(new LlmChatOutcome.Truncated(MODEL, 9, 2048));
        assertThat(listener.texts).containsExactly("cut");
        assertThat(meters.get("llm.requests").tag("outcome", "truncated").timer().count()).isEqualTo(1);
    }

    @Test
    void aMaxTokensFinishWithOnlyThoughtsIsTruncatedWithNoText() {
        handler = (call, out) -> event(out, "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,"
                + "\"text\":\"hmm\"}]},\"finishReason\":\"MAX_TOKENS\"}]}");
        Recorder listener = new Recorder();

        assertThat(client().stream(request(), listener)).isInstanceOf(LlmChatOutcome.Truncated.class);
        assertThat(listener.texts).isEmpty();
    }

    @Test
    void anotherNonStopTerminalFinishFailsUpstream() {
        handler = (call, out) -> event(out,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"cut\"}]},\"finishReason\":\"OTHER\"}]}");

        assertThat(client().stream(request(), new Recorder()))
                .isEqualTo(new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
    }

    @Test
    void theThinkingBudgetIsSentOnlyWhenTheRequestCarriesOne() throws Exception {
        handler = (call, out) -> event(out, lastChunk("ok"));

        client().stream(request(), new Recorder());
        client().stream(new LlmChatRequest(MODEL, null, List.of(new LlmTurn.User("Hi")), List.of(), 0.4, 4096, "en",
                1024), new Recorder());

        assertThat(JSON.readTree(requestBodies.get(0)).path("generationConfig").has("thinkingConfig")).isFalse();
        JsonNode config = JSON.readTree(requestBodies.get(1)).path("generationConfig");
        assertThat(config.path("thinkingConfig").path("thinkingBudget").asInt()).isEqualTo(1024);
        assertThat(config.path("maxOutputTokens").asInt()).isEqualTo(4096);
    }

    @Test
    void cancellingBetweenChunksStopsReading() {
        handler = (call, out) -> {
            event(out, textChunk("one"));
            release.await(10, TimeUnit.SECONDS);
            event(out, textChunk("two"));
        };
        Recorder listener = new Recorder();
        listener.cancelAfterFirstText = true;

        LlmChatOutcome outcome = client().stream(request(), listener);

        assertThat(outcome).isEqualTo(new LlmChatOutcome.Cancelled());
        assertThat(listener.texts).containsExactly("one");
        assertThat(meters.get("llm.requests").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
    }

    @Test
    void cancellingWhileAReadBlocksAbortsTheRead() throws Exception {
        handler = (call, out) -> {
            event(out, textChunk("one"));
            release.await(30, TimeUnit.SECONDS);
        };
        Recorder listener = new Recorder();
        // The stub holds the stream open for 30 s and the idle timeout is 10 s: only the abort the watcher
        // makes on cancel returns sooner, and the 8 s bound sits far above any runner's scheduling jitter.
        CompletableFuture<LlmChatOutcome> outcome = CompletableFuture
                .supplyAsync(() -> client().stream(request(), listener));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (listener.texts.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        listener.requestCancel();

        assertThat(outcome.get(8, TimeUnit.SECONDS)).isEqualTo(new LlmChatOutcome.Cancelled());
        assertThat(listener.texts).containsExactly("one");
    }

    @Test
    void cancellingBeforeTheCallNeverContactsTheProvider() {
        handler = (call, out) -> event(out, lastChunk("never"));
        Recorder listener = new Recorder();
        listener.requestCancel();

        assertThat(client().stream(request(), listener)).isEqualTo(new LlmChatOutcome.Cancelled());
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void anIdleReadPastTheReadTimeoutFailsAsTimeout() {
        handler = (call, out) -> {
            event(out, textChunk("one"));
            release.await(10, TimeUnit.SECONDS);
        };
        Recorder listener = new Recorder();
        long started = System.nanoTime();

        LlmChatOutcome outcome = client(KEY, Duration.ofMillis(400)).stream(request(), listener);

        assertThat(outcome).isEqualTo(new LlmChatOutcome.Failed(LlmJsonResult.Kind.TIMEOUT));
        assertThat(listener.texts).containsExactly("one");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3000);
    }

    @Test
    void aConnectionRefusedIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        GeminiProperties properties = new GeminiProperties(KEY,
                URI.create("http://127.0.0.1:" + closedPort + "/v1beta"), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        GeminiChatClient unreachable = new GeminiChatClient(properties,
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), meters);

        assertThat(unreachable.stream(request(), new Recorder()))
                .isEqualTo(new LlmChatOutcome.Failed(LlmJsonResult.Kind.UNREACHABLE));
    }

    @Test
    void aRejectedKeyFailsUpstream() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });

        assertThat(client().stream(request(), new Recorder()))
                .isEqualTo(new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
        assertThat(meters.get("llm.requests").tag("outcome", "config_error").timer().count()).isEqualTo(1);
    }

    @Test
    void aMissingKeyFailsUpstreamWithoutACall() {
        assertThat(client("", Duration.ofSeconds(1)).stream(request(), new Recorder()))
                .isEqualTo(new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void aTwoRoundToolLoopReplaysTheFunctionCallWithItsExactSignature() throws Exception {
        handler = (call, out) -> {
            if (call == 0) {
                event(out, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
                        + "{\"functionCall\":{\"name\":\"list_workflows\",\"args\":{\"q\":\"video\"}},"
                        + "\"thoughtSignature\":\"Cg0K+/sig==\"}]},\"finishReason\":\"STOP\"}]}");
            } else {
                event(out, lastChunk("Here you go"));
            }
        };
        GeminiChatClient client = client();
        List<LlmTurn> turns = new ArrayList<>(List.of(new LlmTurn.User("Which workflows?")));

        LlmChatOutcome first = client.stream(request("en", turns), new Recorder());
        LlmChatOutcome.ToolCall asked = ((LlmChatOutcome.Finished) first).toolCalls().get(0);
        turns.add(new LlmTurn.FunctionCall(asked.name(), asked.argsJson(), asked.providerState()));
        turns.add(new LlmTurn.FunctionResult("list_workflows", "{\"workflows\":[\"a\"]}"));
        LlmChatOutcome second = client.stream(request("en", turns), new Recorder());

        assertThat(second).isInstanceOf(LlmChatOutcome.Finished.class);
        JsonNode contents = JSON.readTree(requestBodies.get(1)).path("contents");
        assertThat(contents).hasSize(3);
        JsonNode callContent = contents.path(1);
        assertThat(callContent.path("role").asString()).isEqualTo("model");
        assertThat(callContent.path("parts").path(0).path("functionCall").path("name").asString())
                .isEqualTo("list_workflows");
        assertThat(callContent.path("parts").path(0).path("functionCall").path("args").path("q").asString())
                .isEqualTo("video");
        assertThat(callContent.path("parts").path(0).path("thoughtSignature").asString()).isEqualTo("Cg0K+/sig==");
        JsonNode resultContent = contents.path(2);
        assertThat(resultContent.path("role").asString()).isEqualTo("user");
        assertThat(resultContent.path("parts").path(0).path("functionResponse").path("name").asString())
                .isEqualTo("list_workflows");
        assertThat(resultContent.path("parts").path(0).path("functionResponse").path("response")
                .path("workflows").path(0).asString()).isEqualTo("a");
    }

    @Test
    void requestBoundsMatchTheJsonRequest() {
        List<LlmTurn> turns = List.of(new LlmTurn.User("Hi"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new LlmChatRequest(" ", null, turns, List.of(), 0.2, 100, "en"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new LlmChatRequest(MODEL, null, List.of(), List.of(), 0.2, 100, "en"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new LlmChatRequest(MODEL, null, turns, List.of(), 0.2, 0, "en"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new LlmChatRequest(MODEL, null, turns, List.of(), 0.2, 32769, "en"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new LlmChatRequest(MODEL, null, turns, List.of(), 2.5, 100, "en"));
        assertThat(new LlmChatRequest(MODEL, null, turns, null, 0.2, 100, null).language()).isEqualTo("en");
    }
}
