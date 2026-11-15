package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmJsonRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.gemini.GeminiAttempts.Attempt;
import com.flowcortex.llm.gemini.GeminiAttempts.Interpreted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmClient} over the Gemini {@code generateContent} endpoint in JSON mode: exactly one request
 * to the named model, no retry and no fallback. Logs one line per call with the model, outcome, latency
 * and token counts only: never the prompt, the answer, the provider's error body or the key.
 */
final class GeminiLlmClient implements LlmClient {

    static final String SLOT = "direct";

    private final GeminiProperties properties;

    private final RestClient restClient;

    private final GeminiTelemetry telemetry;

    private final String endpoint;

    GeminiLlmClient(GeminiProperties properties, RestClient restClient, MeterRegistry meters) {
        this(properties, restClient, meters, ObservationRegistry.NOOP);
    }

    GeminiLlmClient(GeminiProperties properties, RestClient restClient, MeterRegistry meters,
            ObservationRegistry observations) {
        this.properties = properties;
        this.restClient = restClient;
        this.telemetry = new GeminiTelemetry(meters, observations);
        this.endpoint = properties.endpoint() + "/models/{model}:generateContent";
    }

    @Override
    public LlmJsonResult generateJson(LlmJsonRequest request) {
        String model = request.model();
        if (!properties.hasApiKey()) {
            telemetry.record(SLOT, model, 1, "config_error", 0, 0, " reason=missing_api_key");
            return new LlmJsonResult.Failed(LlmJsonResult.Kind.REJECTED);
        }
        byte[] body = GeminiAttempts.JSON.writeValueAsBytes(body(request));
        Observation observation = telemetry.start(SLOT, model, 1);
        long started = System.nanoTime();
        Attempt attempt;
        try (Observation.Scope ignored = observation.openScope()) {
            attempt = GeminiAttempts.send(restClient, properties.apiKey(), body, endpoint, model);
        }
        if (!attempt.ok()) {
            String outcome = attempt.interrupted() ? "interrupted" : GeminiAttempts.outcome(attempt.kind());
            String detail = attempt.error() == null ? "" : " error=" + attempt.error();
            telemetry.finish(observation, SLOT, model, 1, outcome, attempt.status(), started, detail);
            return new LlmJsonResult.Failed(attempt.kind());
        }
        Interpreted interpreted = GeminiAttempts.interpretJson(model, attempt.body());
        telemetry.finish(observation, SLOT, model, 1, interpreted.outcome(), attempt.status(), started,
                interpreted.detail());
        return interpreted.result();
    }

    private static ObjectNode body(LlmJsonRequest request) {
        ObjectNode body = GeminiAttempts.JSON.createObjectNode();
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
                config.set("responseSchema", GeminiAttempts.JSON.readTree(request.responseSchemaJson()));
            } catch (JacksonException ex) {
                throw new IllegalArgumentException("responseSchemaJson is not valid JSON", ex);
            }
        }
        return body;
    }
}
