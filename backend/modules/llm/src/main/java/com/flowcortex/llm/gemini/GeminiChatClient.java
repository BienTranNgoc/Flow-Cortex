package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatListener;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmTool;
import com.flowcortex.llm.api.LlmTurn;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmChatClient} over the Gemini {@code streamGenerateContent} endpoint with server-sent
 * events. Uses the JDK client because closing its response stream aborts a read that is blocked in
 * the socket, which a cancelled chat needs. Logs one line per round with model, outcome, latency and
 * token counts only: never the prompt, the answer, tool arguments, the provider's error body or the key.
 */
final class GeminiChatClient implements LlmChatClient {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiChatClient.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String KEY_HEADER = "x-goog-api-key";

    private static final String DATA_PREFIX = "data:";

    private static final Set<String> BLOCKING_FINISH_REASONS = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST",
            "SPII");

    private static final int OK = 200;

    private static final int TOO_MANY_REQUESTS = 429;

    private static final int CLIENT_ERROR = 400;

    private static final int SERVER_ERROR = 500;

    private static final long WATCH_POLL_MILLIS = 100;

    private final GeminiProperties properties;

    private final HttpClient http;

    private final MeterRegistry meters;

    private final String endpointTemplate;

    GeminiChatClient(GeminiProperties properties, HttpClient http, MeterRegistry meters) {
        this.properties = properties;
        this.http = http;
        this.meters = meters;
        String base = properties.baseUrl().toString();
        this.endpointTemplate = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/models/{model}:streamGenerateContent?alt=sse";
    }

    @Override
    public LlmChatOutcome stream(LlmChatRequest request, LlmChatListener listener) {
        String model = request.model();
        long started = System.nanoTime();
        if (listener.cancelled()) {
            return finish(model, "cancelled", started, new LlmChatOutcome.Cancelled());
        }
        if (!properties.hasApiKey()) {
            LOG.error("llm.request mode=stream outcome=config_error reason=missing_api_key model={}", model);
            return finish(model, "config_error", started, new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(model))
                .timeout(properties.readTimeout())
                .header(KEY_HEADER, properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body(request))))
                .build();
        Watch watch = new Watch(listener, properties.readTimeout());
        Thread watcher = Thread.ofVirtual().name("llm-chat-watch").start(watch);
        try {
            return exchange(model, httpRequest, listener, watch, started);
        } finally {
            watch.done = true;
            watcher.interrupt();
        }
    }

    private LlmChatOutcome exchange(String model, HttpRequest httpRequest, LlmChatListener listener, Watch watch,
            long started) {
        CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(httpRequest,
                HttpResponse.BodyHandlers.ofInputStream());
        watch.pending = pending;
        HttpResponse<InputStream> response;
        try {
            response = pending.get();
        } catch (CancellationException ex) {
            return finish(model, "cancelled", started, new LlmChatOutcome.Cancelled());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed(model, "upstream", LlmJsonResult.Kind.UPSTREAM, started, ex);
        } catch (ExecutionException ex) {
            return ioFailure(model, ex.getCause() == null ? ex : ex.getCause(), watch, started);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != OK) {
                return httpError(model, response.statusCode(), started);
            }
            watch.body = in;
            watch.touch();
            return read(model, in, listener, watch, started);
        } catch (IOException ex) {
            return ioFailure(model, ex, watch, started);
        }
    }

    private LlmChatOutcome read(String model, InputStream in, LlmChatListener listener, Watch watch, long started)
            throws IOException {
        Round round = new Round();
        BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                watch.touch();
                if (line.startsWith(DATA_PREFIX) && !handle(line.substring(DATA_PREFIX.length()).strip(), round,
                        listener)) {
                    return failed(model, "upstream", LlmJsonResult.Kind.UPSTREAM, started,
                            new IOException("malformed_event"));
                }
                if (round.blockReason != null || round.finishReason != null) {
                    break;
                }
                if (listener.cancelled()) {
                    return finish(model, "cancelled", started, new LlmChatOutcome.Cancelled());
                }
            }
        } catch (IOException ex) {
            return ioFailure(model, ex, watch, started);
        }
        return conclude(model, round, watch, listener, started);
    }

    private LlmChatOutcome conclude(String model, Round round, Watch watch, LlmChatListener listener, long started) {
        if (round.blockReason != null) {
            LOG.info("llm.request mode=stream outcome=blocked reason={} model={} latency_ms={} input_tokens={} "
                    + "output_tokens={}", round.blockReason, model, millisSince(started), round.inputTokens,
                    round.outputTokens);
            return finish(model, "blocked", started, new LlmChatOutcome.Blocked(round.blockReason));
        }
        if (watch.cancelledByWatch || listener.cancelled()) {
            return finish(model, "cancelled", started, new LlmChatOutcome.Cancelled());
        }
        if (watch.idleExpired) {
            return failed(model, "timeout", LlmJsonResult.Kind.TIMEOUT, started, null);
        }
        String answered = round.version == null ? model : round.version;
        if ("MAX_TOKENS".equals(round.finishReason)) {
            LOG.warn("llm.request mode=stream outcome=truncated model={} latency_ms={} input_tokens={} "
                    + "output_tokens={}", answered, millisSince(started), round.inputTokens, round.outputTokens);
            return finish(model, "truncated", started,
                    new LlmChatOutcome.Truncated(answered, round.inputTokens, round.outputTokens));
        }
        if (!"STOP".equals(round.finishReason)) {
            LOG.warn("llm.request mode=stream outcome=upstream model={} latency_ms={} finish_reason={} "
                    + "error=unusable_stream", model, millisSince(started), round.finishReason);
            return finish(model, "upstream", started, new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
        }
        LOG.info("llm.request mode=stream outcome=answered model={} latency_ms={} input_tokens={} output_tokens={} "
                + "tool_calls={}", answered, millisSince(started), round.inputTokens, round.outputTokens,
                round.toolCalls.size());
        return finish(model, "answered", started, new LlmChatOutcome.Finished(answered, round.inputTokens,
                round.outputTokens, round.toolCalls));
    }

    /** Applies one event; false when the payload is not JSON. */
    private static boolean handle(String payload, Round round, LlmChatListener listener) {
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return true;
        }
        JsonNode event;
        try {
            event = JSON.readTree(payload);
        } catch (JacksonException ex) {
            return false;
        }
        JsonNode usage = event.path("usageMetadata");
        if (usage.isObject()) {
            round.inputTokens = usage.path("promptTokenCount").asInt(round.inputTokens);
            round.outputTokens = usage.path("candidatesTokenCount").asInt(round.outputTokens);
        }
        String version = textOrNull(event.path("modelVersion"));
        if (version != null) {
            round.version = version;
        }
        String promptBlock = textOrNull(event.path("promptFeedback").path("blockReason"));
        if (promptBlock != null) {
            round.blockReason = promptBlock;
            return true;
        }
        JsonNode candidate = event.path("candidates").path(0);
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            if (part.path("text").isString() && !part.path("text").asString().isEmpty()) {
                listener.onText(part.path("text").asString());
            } else if (part.path("functionCall").isObject()) {
                JsonNode call = part.path("functionCall");
                JsonNode args = call.path("args");
                String argsJson = args.isMissingNode() || args.isNull() ? "{}" : JSON.writeValueAsString(args);
                String signature = textOrNull(part.path("thoughtSignature"));
                String name = call.path("name").asString();
                listener.onToolCall(name, argsJson, signature);
                round.toolCalls.add(new LlmChatOutcome.ToolCall(name, argsJson, signature));
            }
        }
        String finishReason = textOrNull(candidate.path("finishReason"));
        if (finishReason != null) {
            round.finishReason = finishReason;
            if (BLOCKING_FINISH_REASONS.contains(finishReason)) {
                round.blockReason = finishReason;
            }
        }
        return true;
    }

    private URI endpoint(String model) {
        return UriComponentsBuilder.fromUriString(endpointTemplate).buildAndExpand(model).encode().toUri();
    }

    private static ObjectNode body(LlmChatRequest request) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode instruction = body.putObject("systemInstruction").putArray("parts");
        if (request.systemInstruction() != null) {
            instruction.addObject().put("text", request.systemInstruction());
        }
        instruction.addObject().put("text", "Answer in " + ("vi".equals(request.language()) ? "Vietnamese" : "English")
                + ".");
        ArrayNode contents = body.putArray("contents");
        for (LlmTurn turn : request.turns()) {
            switch (turn) {
                case LlmTurn.User user -> partsFor(contents, "user").addObject().put("text", user.text());
                case LlmTurn.Model model -> partsFor(contents, "model").addObject().put("text", model.text());
                case LlmTurn.FunctionCall call -> {
                    ObjectNode part = partsFor(contents, "model").addObject();
                    part.putObject("functionCall").put("name", call.name()).set("args", json(call.argsJson()));
                    if (call.providerState() != null) {
                        part.put("thoughtSignature", call.providerState());
                    }
                }
                case LlmTurn.FunctionResult result -> partsFor(contents, "user").addObject()
                        .putObject("functionResponse").put("name", result.name())
                        .set("response", responseObject(result.resultJson()));
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (LlmTool tool : request.tools()) {
                ObjectNode declaration = declarations.addObject().put("name", tool.name());
                if (tool.description() != null) {
                    declaration.put("description", tool.description());
                }
                if (tool.parametersSchemaJson() != null && !tool.parametersSchemaJson().isBlank()) {
                    declaration.set("parameters", json(tool.parametersSchemaJson()));
                }
            }
        }
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", request.temperature());
        config.put("maxOutputTokens", request.maxOutputTokens());
        if (request.thinkingBudget() != null) {
            config.putObject("thinkingConfig").put("thinkingBudget", request.thinkingBudget());
        }
        return body;
    }

    /** Consecutive turns of one role share a content entry, as parallel calls and their results must. */
    private static ArrayNode partsFor(ArrayNode contents, String role) {
        if (!contents.isEmpty() && role.equals(contents.get(contents.size() - 1).path("role").asString())) {
            return (ArrayNode) contents.get(contents.size() - 1).path("parts");
        }
        ObjectNode content = contents.addObject();
        content.put("role", role);
        return content.putArray("parts");
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("not valid JSON: " + text.length() + " chars", ex);
        }
    }

    /** The provider takes an object as a function's response; any other value goes under {@code result}. */
    private static JsonNode responseObject(String resultJson) {
        JsonNode node = json(resultJson);
        if (node.isObject()) {
            return node;
        }
        ObjectNode wrapped = JSON.createObjectNode();
        wrapped.set("result", node);
        return wrapped;
    }

    private LlmChatOutcome httpError(String model, int status, long started) {
        if (status >= CLIENT_ERROR && status < SERVER_ERROR && status != TOO_MANY_REQUESTS) {
            // A rejected key or an unknown model is a deployment mistake, not an outage.
            LOG.error("llm.request mode=stream outcome=config_error status={} model={} latency_ms={}", status, model,
                    millisSince(started));
            return finish(model, "config_error", started, new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
        }
        LOG.warn("llm.request mode=stream outcome=upstream status={} model={} latency_ms={}", status, model,
                millisSince(started));
        return finish(model, "upstream", started, new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
    }

    /** A failure raised by an abort the watcher made is the cancel or idle timeout, not an upstream fault. */
    private LlmChatOutcome ioFailure(String model, Throwable cause, Watch watch, long started) {
        if (watch.cancelledByWatch) {
            return finish(model, "cancelled", started, new LlmChatOutcome.Cancelled());
        }
        if (watch.idleExpired) {
            return failed(model, "timeout", LlmJsonResult.Kind.TIMEOUT, started, cause);
        }
        LlmJsonResult.Kind kind = LlmJsonResult.Kind.UPSTREAM;
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
            kind = LlmJsonResult.Kind.UNREACHABLE;
        } else if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            kind = LlmJsonResult.Kind.TIMEOUT;
        }
        return failed(model, kind.name().toLowerCase(Locale.ROOT), kind, started, cause);
    }

    private LlmChatOutcome failed(String model, String outcome, LlmJsonResult.Kind kind, long started,
            Throwable cause) {
        LOG.warn("llm.request mode=stream outcome={} model={} latency_ms={} error={}", outcome, model,
                millisSince(started), cause == null ? "idle_timeout" : cause.getClass().getSimpleName());
        return finish(model, outcome, started, new LlmChatOutcome.Failed(kind));
    }

    private LlmChatOutcome finish(String model, String outcome, long started, LlmChatOutcome result) {
        Timer.builder("llm.requests")
                .description("Calls to the language model provider, by model and outcome")
                .tag("model", model)
                .tag("outcome", outcome)
                .register(meters)
                .record(millisSince(started), TimeUnit.MILLISECONDS);
        return result;
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static long millisSince(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /** What one round has produced so far. */
    private static final class Round {
        private int inputTokens;
        private int outputTokens;
        private String version;
        private String finishReason;
        private String blockReason;
        private final List<LlmChatOutcome.ToolCall> toolCalls = new ArrayList<>();
    }

    /**
     * Aborts the exchange when the listener cancels or no line arrives within the read timeout: closing
     * the response stream is what frees a read blocked in the socket.
     */
    private static final class Watch implements Runnable {

        private final LlmChatListener listener;

        private final long idleNanos;

        private volatile boolean done;

        private volatile boolean cancelledByWatch;

        private volatile boolean idleExpired;

        private volatile long lastActivity = System.nanoTime();

        private volatile CompletableFuture<?> pending;

        private volatile InputStream body;

        Watch(LlmChatListener listener, Duration readTimeout) {
            this.listener = listener;
            this.idleNanos = readTimeout.toNanos();
        }

        void touch() {
            lastActivity = System.nanoTime();
        }

        @Override
        public void run() {
            while (!done) {
                try {
                    Thread.sleep(WATCH_POLL_MILLIS);
                } catch (InterruptedException ex) {
                    return;
                }
                if (done) {
                    return;
                }
                if (listener.cancelled()) {
                    cancelledByWatch = true;
                    abort();
                } else if (body != null && System.nanoTime() - lastActivity > idleNanos) {
                    idleExpired = true;
                    abort();
                }
            }
        }

        private void abort() {
            CompletableFuture<?> future = pending;
            if (future != null) {
                future.cancel(true);
            }
            InputStream stream = body;
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ex) {
                    // Already closing: the reader sees the failure.
                }
            }
        }
    }
}
