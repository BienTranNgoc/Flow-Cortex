package com.flowcortex.workflow.application.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Gemini API for the dub steps: a JSON answer from a translation model, and one voice file
 * for a whole run from a speech model.
 *
 * <p>A translation request tries each model in turn; on a model, a quota refusal, a server error or
 * a call that does not answer in time is tried again after the next configured wait, and any other
 * refusal moves straight on. A speech request moves to the next voice model at once on a quota
 * refusal, because a daily quota does not come back within any wait, and after a server error or a
 * missed answer on every attempt; a next voice model gets that one request only, and any other
 * refusal ends the request. An interrupt ends it as an {@link IllegalStateException}, not as a
 * provider failure. Each call is one {@value #OBSERVATION} observation and one log line with model,
 * attempt, outcome and latency — never the request, the answer or the key.
 */
public class GeminiClient {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiClient.class);
    static final String OBSERVATION = "dub.provider.call";

    /** Waits between attempts; tests replace it so they do not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration wait) throws InterruptedException;
    }

    /** One voice file (RIFF WAV) and the model that voiced it. */
    public record Speech(byte[] wav, String model) {
    }

    private enum Outcome {
        OK("ok"),
        QUOTA_EXCEEDED("quota_exceeded"),
        SERVER_ERROR("server_error"),
        UNREACHABLE("unreachable"),
        REFUSED("refused"),
        INTERRUPTED("interrupted");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }
    }

    private record Call(Outcome outcome, int status, byte[] body) {

        static Call of(int status, byte[] body) {
            Outcome outcome;
            if (status >= 200 && status < 300) {
                outcome = Outcome.OK;
            } else if (status == 429) {
                outcome = Outcome.QUOTA_EXCEEDED;
            } else if (status >= 500) {
                outcome = Outcome.SERVER_ERROR;
            } else {
                outcome = Outcome.REFUSED;
            }
            return new Call(outcome, status, body);
        }
    }

    private record Answer(String model, byte[] body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http;
    private final DubProperties.Gemini config;
    private final Sleeper sleeper;
    private final ObservationRegistry observations;

    public GeminiClient(RestClient.Builder builder, DubProperties.Gemini config, Sleeper sleeper,
            ObservationRegistry observations) {
        this.http = builder.baseUrl(config.baseUrl())
                .defaultHeader("x-goog-api-key", config.apiKey() == null ? "" : config.apiKey())
                .build();
        this.config = config;
        this.sleeper = sleeper;
        this.observations = observations;
    }

    /** A client whose every call gives up after the configured call timeout. */
    public static GeminiClient create(DubProperties.Gemini config, Sleeper sleeper, ObservationRegistry observations) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.callTimeout()).build());
        factory.setReadTimeout(config.callTimeout());
        return new GeminiClient(RestClient.builder().requestFactory(factory), config, sleeper, observations);
    }

    /**
     * Asks the translation models for a JSON answer following {@code responseSchema}.
     * @return the answer's text, or {@code null} when the model answered without one
     * @throws GeminiException when no model answered
     */
    public String generateJson(String prompt, String responseSchema) {
        ObjectNode body = json.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.set("responseSchema", schema(responseSchema));
        String request = write(body);
        Answer answer = request("translate", config.translateModels(),
                model -> "/models/" + model + ":generateContent", model -> request, false);
        JsonNode text = read(answer.body()).path("candidates").path(0).path("content").path("parts").path(0)
                .path("text");
        return text.isTextual() ? text.asText() : null;
    }

    /**
     * Voices {@code input} with {@code voice} in one request.
     * @throws GeminiException when no voice model answered with audio
     */
    public Speech speak(String input, String voice) {
        Answer answer = request("tts", config.ttsModels(), model -> "/interactions", model -> {
            ObjectNode body = json.createObjectNode();
            body.put("model", model);
            body.put("input", input);
            body.putObject("generation_config").putArray("speech_config").addObject().put("voice", voice);
            return write(body);
        }, true);
        for (JsonNode step : read(answer.body()).path("steps")) {
            for (JsonNode content : step.path("content")) {
                if (content.path("data").isTextual()) {
                    try {
                        return new Speech(Base64.getDecoder().decode(content.path("data").asText()), answer.model());
                    } catch (IllegalArgumentException e) {
                        break;
                    }
                }
            }
        }
        LOG.warn("Provider answered without audio: model={}", answer.model());
        throw new GeminiException(GeminiException.Reason.UNAVAILABLE, answer.model());
    }

    private Answer request(String operation, List<String> models, Function<String, String> path,
            Function<String, String> body, boolean speech) {
        // A spent daily quota outlasts any outage of a later model, so it is what the member is told.
        boolean quotaSpent = false;
        String model = models.getFirst();
        for (int index = 0; index < models.size(); index++) {
            model = models.get(index);
            String request = body.apply(model);
            // A fallback voice model gets one request: the voice file is wanted now, and each request spends
            // one of a handful of daily requests.
            int attempts = speech && index > 0 ? 1 : config.attempts();
            boolean refused = false;
            attempts:
            for (int attempt = 1; attempt <= attempts; attempt++) {
                Call call = send(operation, model, attempt, path.apply(model), request);
                switch (call.outcome()) {
                    case OK -> {
                        return new Answer(model, call.body());
                    }
                    case QUOTA_EXCEEDED -> {
                        quotaSpent = true;
                        if (speech) {
                            break attempts;
                        }
                    }
                    case REFUSED -> {
                        refused = true;
                        break attempts;
                    }
                    case INTERRUPTED -> throw interrupted(model, null);
                    // SERVER_ERROR and UNREACHABLE: tried again after the wait.
                    default -> { }
                }
                if (attempt < attempts) {
                    pause(config.retryWaits().get(attempt - 1), model);
                }
            }
            if (speech && refused) {
                break;
            }
        }
        throw new GeminiException(quotaSpent ? GeminiException.Reason.QUOTA_EXCEEDED
                : GeminiException.Reason.UNAVAILABLE, model);
    }

    private Call send(String operation, String model, int attempt, String path, String request) {
        Observation observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("model", model)
                .highCardinalityKeyValue("attempt", String.valueOf(attempt))
                .start();
        long started = System.nanoTime();
        Call call;
        String failure = "none";
        try (Observation.Scope ignored = observation.openScope()) {
            call = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((sent, response) -> Call.of(response.getStatusCode().value(),
                            response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            // Not answered in time, not reachable, or interrupted (the client keeps the thread's interrupt flag).
            // Only the cause's type is kept: its text may carry the URL.
            call = new Call(Thread.currentThread().isInterrupted() ? Outcome.INTERRUPTED : Outcome.UNREACHABLE, 0,
                    new byte[0]);
            failure = (e.getCause() != null ? e.getCause() : e).getClass().getSimpleName();
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        observation.lowCardinalityKeyValue("outcome", call.outcome().value)
                .highCardinalityKeyValue("latencyMs", String.valueOf(latencyMs))
                .stop();
        String status = call.status() == 0 ? "none" : String.valueOf(call.status());
        if (call.outcome() == Outcome.OK) {
            LOG.info("Provider call: operation={} model={} attempt={} outcome={} status={} latencyMs={}", operation,
                    model, attempt, call.outcome().value, status, latencyMs);
        } else {
            LOG.warn("Provider call: operation={} model={} attempt={} outcome={} status={} latencyMs={} error={}",
                    operation, model, attempt, call.outcome().value, status, latencyMs, failure);
        }
        return call;
    }

    private void pause(Duration wait, String model) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted(model, e);
        }
    }

    /** The backend is stopping the step, not the provider failing it, so this carries no provider code. */
    private static IllegalStateException interrupted(String model, InterruptedException cause) {
        return new IllegalStateException("Interrupted while calling " + model, cause);
    }

    private JsonNode schema(String responseSchema) {
        try {
            return json.readTree(responseSchema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The response schema is not JSON", e);
        }
    }

    /** The answer as JSON; anything else reads as an empty answer. Parse errors quote the input, so none is kept. */
    private JsonNode read(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            return node == null ? MissingNode.getInstance() : node;
        } catch (IOException e) {
            return MissingNode.getInstance();
        }
    }

    private String write(JsonNode body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A provider request could not be serialized");
        }
    }
}
