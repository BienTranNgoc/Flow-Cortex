package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmJsonRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmClient} over the Gemini {@code generateContent} endpoint in JSON mode. Logs one line per
 * call with the model, outcome, latency and token counts only: never the prompt, the answer, the
 * provider's error body or the key.
 */
final class GeminiLlmClient implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiLlmClient.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String KEY_HEADER = "x-goog-api-key";

    private static final Set<String> BLOCKING_FINISH_REASONS = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST",
            "SPII");

    private static final int TOO_MANY_REQUESTS = 429;

    private final GeminiProperties properties;

    private final RestClient restClient;

    private final MeterRegistry meters;

    private final String endpoint;

    GeminiLlmClient(GeminiProperties properties, RestClient restClient, MeterRegistry meters) {
        this.properties = properties;
        this.restClient = restClient;
        this.meters = meters;
        String base = properties.baseUrl().toString();
        this.endpoint = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/models/{model}:generateContent";
    }

    @Override
    public LlmJsonResult generateJson(LlmJsonRequest request) {
        String model = request.model();
        if (!properties.hasApiKey()) {
            LOG.error("llm.request outcome=config_error reason=missing_api_key model={}", model);
            return finish(model, "config_error", 0, new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM));
        }
        byte[] body = JSON.writeValueAsBytes(body(request));
        long started = System.nanoTime();
        byte[] response;
        try {
            response = restClient.post()
                    .uri(endpoint, model)
                    .header(KEY_HEADER, properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException ex) {
            return httpError(model, ex.getStatusCode(), started);
        } catch (ResourceAccessException ex) {
            return ioError(model, ex, started);
        } catch (RestClientException ex) {
            LOG.warn("llm.request outcome=upstream model={} latency_ms={} error={}", model, millisSince(started),
                    ex.getClass().getSimpleName());
            return failed(model, "upstream", LlmJsonResult.Kind.UPSTREAM, started);
        }
        return interpret(model, response, started);
    }

    private static ObjectNode body(LlmJsonRequest request) {
        ObjectNode body = JSON.createObjectNode();
        if (request.systemInstruction() != null) {
            body.putObject("systemInstruction").putArray("parts").addObject()
                    .put("text", request.systemInstruction());
        }
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", request.userText());
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", request.temperature());
        config.put("maxOutputTokens", request.maxOutputTokens());
        config.put("responseMimeType", "application/json");
        if (request.responseSchemaJson() != null) {
            try {
                config.set("responseSchema", JSON.readTree(request.responseSchemaJson()));
            } catch (JacksonException ex) {
                throw new IllegalArgumentException("responseSchemaJson is not valid JSON", ex);
            }
        }
        return body;
    }

    private LlmJsonResult httpError(String model, HttpStatusCode status, long started) {
        if (status.is4xxClientError() && status.value() != TOO_MANY_REQUESTS) {
            // A rejected key or an unknown model is a deployment mistake, not an outage.
            LOG.error("llm.request outcome=config_error status={} model={} latency_ms={}", status.value(), model,
                    millisSince(started));
            return failed(model, "config_error", LlmJsonResult.Kind.UPSTREAM, started);
        }
        LOG.warn("llm.request outcome=upstream status={} model={} latency_ms={}", status.value(), model,
                millisSince(started));
        return failed(model, "upstream", LlmJsonResult.Kind.UPSTREAM, started);
    }

    private LlmJsonResult ioError(String model, ResourceAccessException ex, long started) {
        LlmJsonResult.Kind kind = LlmJsonResult.Kind.UPSTREAM;
        Throwable cause = ex.getCause();
        if (cause instanceof SocketTimeoutException) {
            kind = LlmJsonResult.Kind.TIMEOUT;
        } else if (cause instanceof ConnectException || cause instanceof UnknownHostException
                || cause instanceof NoRouteToHostException) {
            kind = LlmJsonResult.Kind.UNREACHABLE;
        }
        String outcome = kind.name().toLowerCase(Locale.ROOT);
        LOG.warn("llm.request outcome={} model={} latency_ms={} error={}", outcome, model, millisSince(started),
                cause == null ? ex.getClass().getSimpleName() : cause.getClass().getSimpleName());
        return failed(model, outcome, kind, started);
    }

    private LlmJsonResult interpret(String model, byte[] response, long started) {
        JsonNode root;
        try {
            root = response == null ? JSON.missingNode() : JSON.readTree(response);
        } catch (JacksonException ex) {
            LOG.warn("llm.request outcome=upstream model={} latency_ms={} error=malformed_response", model,
                    millisSince(started));
            return failed(model, "upstream", LlmJsonResult.Kind.UPSTREAM, started);
        }
        JsonNode usage = root.path("usageMetadata");
        int inputTokens = usage.path("promptTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);

        String promptBlock = textOrNull(root.path("promptFeedback").path("blockReason"));
        JsonNode candidate = root.path("candidates").path(0);
        String finishReason = textOrNull(candidate.path("finishReason"));
        String blockReason = promptBlock != null ? promptBlock
                : BLOCKING_FINISH_REASONS.contains(finishReason) ? finishReason : null;
        if (blockReason != null) {
            LOG.info("llm.request outcome=blocked reason={} model={} latency_ms={} input_tokens={} output_tokens={}",
                    blockReason, model, millisSince(started), inputTokens, outputTokens);
            return finish(model, "blocked", millisSince(started), new LlmJsonResult.Blocked(blockReason));
        }

        String text = answerText(candidate);
        if (text == null || (finishReason != null && !"STOP".equals(finishReason)) || !isJson(text)) {
            LOG.warn("llm.request outcome=upstream model={} latency_ms={} finish_reason={} error=unusable_answer",
                    model, millisSince(started), finishReason);
            return failed(model, "upstream", LlmJsonResult.Kind.UPSTREAM, started);
        }
        String version = textOrNull(root.path("modelVersion"));
        long latency = millisSince(started);
        LOG.info("llm.request outcome=answered model={} latency_ms={} input_tokens={} output_tokens={}",
                version == null ? model : version, latency, inputTokens, outputTokens);
        return finish(model, "answered", latency,
                new LlmJsonResult.Answered(text, version == null ? model : version, inputTokens, outputTokens));
    }

    /** Joins the answer's text parts; thought parts, if the model returns any, are not the answer. */
    private static String answerText(JsonNode candidate) {
        StringBuilder text = new StringBuilder();
        boolean found = false;
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false) || !part.path("text").isString()) {
                continue;
            }
            text.append(part.path("text").asString());
            found = true;
        }
        return found ? text.toString() : null;
    }

    private static boolean isJson(String text) {
        try {
            JSON.readTree(text);
            return true;
        } catch (JacksonException ex) {
            return false;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private LlmJsonResult failed(String model, String outcome, LlmJsonResult.Kind kind, long started) {
        return finish(model, outcome, millisSince(started), new LlmJsonResult.Failed(kind));
    }

    private LlmJsonResult finish(String model, String outcome, long latencyMillis, LlmJsonResult result) {
        Timer.builder("llm.requests")
                .description("Calls to the language model provider, by model and outcome")
                .tag("model", model)
                .tag("outcome", outcome)
                .register(meters)
                .record(latencyMillis, TimeUnit.MILLISECONDS);
        return result;
    }

    private static long millisSince(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
