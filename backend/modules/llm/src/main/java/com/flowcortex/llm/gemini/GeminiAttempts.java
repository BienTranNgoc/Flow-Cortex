package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmJsonResult.Kind;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Gemini request every call goes through, and how its answer is read. Carries the key in the
 * {@code x-goog-api-key} header only; nothing here keeps a prompt, an answer or a provider message.
 */
final class GeminiAttempts {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String KEY_HEADER = "x-goog-api-key";

    private static final int TOO_MANY_REQUESTS = 429;

    private static final Set<String> BLOCKING_FINISH_REASONS = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST",
            "SPII");

    private static final int MAX_CAUSE_DEPTH = 5;

    private GeminiAttempts() {
    }

    /**
     * One request and what came back. {@code kind} is {@code null} for a 2xx. {@code error} names the
     * exception type of an I/O failure and is {@code null} otherwise; {@code interrupted} says the
     * calling thread was interrupted during the call.
     */
    record Attempt(Kind kind, int status, byte[] body, boolean interrupted, String error) {

        boolean ok() {
            return kind == null;
        }
    }

    /** What reading a 200 answer came to: the result, the telemetry outcome and log detail. */
    record Interpreted(String outcome, LlmJsonResult result, String detail) {
    }

    private record Raw(int status, byte[] body) {
    }

    /**
     * A client over the shared JDK {@code HttpClient} whose every request gives up after
     * {@code readTimeout}, also when the body stalls, and that aborts when its thread is interrupted.
     */
    static RestClient restClient(HttpClient http, Duration readTimeout) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requests).build();
    }

    static Attempt send(RestClient http, String apiKey, byte[] body, String uriTemplate, Object... uriVariables) {
        return attempt(() -> http.post()
                .uri(uriTemplate, uriVariables)
                .header(KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                        response.getBody().readAllBytes())));
    }

    /** A GET with the key in the header only; the outcome is classified exactly as for {@link #send}. */
    static Attempt get(RestClient http, String apiKey, String uriTemplate, Object... uriVariables) {
        return attempt(() -> http.get()
                .uri(uriTemplate, uriVariables)
                .header(KEY_HEADER, apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                        response.getBody().readAllBytes())));
    }

    private static Attempt attempt(Supplier<Raw> exchange) {
        Raw raw;
        try {
            raw = exchange.get();
        } catch (RestClientException ex) {
            // Only the cause's type is kept: its text may carry the URL.
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return new Attempt(ioKind(ex), 0, new byte[0], Thread.currentThread().isInterrupted(),
                    cause.getClass().getSimpleName());
        }
        return new Attempt(statusKind(raw.status()), raw.status(), raw.body(), false, null);
    }

    static Kind statusKind(int status) {
        if (status >= 200 && status < 300) {
            return null;
        }
        if (status == TOO_MANY_REQUESTS) {
            return Kind.QUOTA_EXCEEDED;
        }
        return status >= 400 && status < 500 ? Kind.REJECTED : Kind.UPSTREAM;
    }

    private static Kind ioKind(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
                return Kind.UNREACHABLE;
            }
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return Kind.TIMEOUT;
            }
        }
        return Kind.UPSTREAM;
    }

    /** The outcome name that the timer, the observation and the log line carry for a failure. */
    static String outcome(Kind kind) {
        return kind == Kind.REJECTED ? "config_error" : kind.name().toLowerCase(Locale.ROOT);
    }

    /** Reads a 200 generateContent answer in JSON mode. */
    static Interpreted interpretJson(String model, byte[] response) {
        JsonNode root;
        try {
            root = response == null ? JSON.missingNode() : JSON.readTree(response);
        } catch (JacksonException ex) {
            return failure(Kind.UNUSABLE, " error=malformed_response");
        }
        JsonNode usage = root.path("usageMetadata");
        int inputTokens = usage.path("promptTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);

        String promptBlock = textOrNull(root.path("promptFeedback").path("blockReason"));
        JsonNode candidate = root.path("candidates").path(0);
        String finishReason = textOrNull(candidate.path("finishReason"));
        String blockReason = promptBlock != null ? promptBlock
                : finishReason != null && BLOCKING_FINISH_REASONS.contains(finishReason) ? finishReason : null;
        if (blockReason != null) {
            return new Interpreted("blocked", new LlmJsonResult.Blocked(blockReason),
                    " reason=" + blockReason + " input_tokens=" + inputTokens + " output_tokens=" + outputTokens);
        }

        String text = answerText(candidate);
        if (text == null || (finishReason != null && !"STOP".equals(finishReason)) || !isJson(text)) {
            return failure(Kind.UNUSABLE, " finish_reason=" + finishReason + " error=unusable_answer");
        }
        String version = textOrNull(root.path("modelVersion"));
        return new Interpreted("answered",
                new LlmJsonResult.Answered(text, version == null ? model : version, inputTokens, outputTokens),
                " input_tokens=" + inputTokens + " output_tokens=" + outputTokens);
    }

    private static Interpreted failure(Kind kind, String detail) {
        return new Interpreted(outcome(kind), new LlmJsonResult.Failed(kind), detail);
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
}
