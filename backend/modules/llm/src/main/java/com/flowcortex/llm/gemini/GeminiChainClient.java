package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmJsonResult.Kind;
import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmSpeechResult;
import com.flowcortex.llm.gemini.GeminiAttempts.Attempt;
import com.flowcortex.llm.gemini.GeminiAttempts.Interpreted;
import com.flowcortex.llm.gemini.GeminiChainPolicy.Mode;
import com.flowcortex.llm.gemini.GeminiChainPolicy.Verdict;
import com.flowcortex.llm.gemini.GeminiSlotProperties.Slot;
import io.micrometer.observation.Observation;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmSlots} over Gemini: walks a slot's models as {@link GeminiChainPolicy} says, one request
 * per attempt through {@link GeminiAttempts}. An interrupt ends the call with an
 * {@link IllegalStateException} before the policy is asked, so an interrupted attempt never becomes a
 * move to the next model.
 */
final class GeminiChainClient implements LlmSlots {

    /** Waits between attempts; tests replace it so they do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration wait) throws InterruptedException;
    }

    private record Chained<T>(T result, Kind failure) {
    }

    /** What one request's 200 answer came to; {@code result} is {@code null} when the attempt failed. */
    private record Read<T>(String outcome, T result, Kind failure, String detail) {
    }

    private final GeminiProperties properties;

    private final GeminiSlotProperties slots;

    private final Map<String, RestClient> clients = new HashMap<>();

    private final Sleeper sleeper;

    private final GeminiTelemetry telemetry;

    GeminiChainClient(GeminiProperties properties, GeminiSlotProperties slots,
            Function<Duration, RestClient> clientFor, Sleeper sleeper, GeminiTelemetry telemetry) {
        this.properties = properties;
        this.slots = slots;
        slots.slots().forEach((name, slot) -> clients.put(name, clientFor.apply(slot.callTimeout())));
        this.sleeper = sleeper;
        this.telemetry = telemetry;
    }

    @Override
    public LlmJsonResult generateJson(String slotName, String userText, String responseSchemaJson) {
        Slot slot = slots.slot(slotName);
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText must not be blank");
        }
        if (!properties.hasApiKey()) {
            return new LlmJsonResult.Failed(missingKey(slotName, slot));
        }
        byte[] body = GeminiAttempts.JSON.writeValueAsBytes(jsonBody(userText, responseSchemaJson));
        Chained<LlmJsonResult> chained = run(slotName, slot, Mode.JSON, model -> body,
                properties.endpoint() + "/models/{model}:generateContent",
                (model, answer) -> {
                    Interpreted interpreted = GeminiAttempts.interpretJson(model, answer);
                    Kind failure = interpreted.result() instanceof LlmJsonResult.Failed failed ? failed.kind() : null;
                    return new Read<>(interpreted.outcome(), interpreted.result(), failure, interpreted.detail());
                });
        return chained.result() != null ? chained.result() : new LlmJsonResult.Failed(chained.failure());
    }

    @Override
    public LlmSpeechResult speak(String slotName, String text, String voice) {
        Slot slot = slots.slot(slotName);
        if (text == null || text.isBlank() || voice == null || voice.isBlank()) {
            throw new IllegalArgumentException("text and voice must not be blank");
        }
        if (!properties.hasApiKey()) {
            return new LlmSpeechResult.Failed(missingKey(slotName, slot));
        }
        Chained<LlmSpeechResult> chained = run(slotName, slot, Mode.SPEECH, model -> speechBody(model, text, voice),
                properties.endpoint() + "/interactions", GeminiChainClient::readSpeech);
        return chained.result() != null ? chained.result() : new LlmSpeechResult.Failed(chained.failure());
    }

    @Override
    public Duration jsonWorstCase(String slot) {
        return slots.slot(slot).jsonWorstCase();
    }

    @Override
    public Duration speechWorstCase(String slot) {
        return slots.slot(slot).speechWorstCase();
    }

    @Override
    public boolean configured() {
        return properties.hasApiKey();
    }

    private Kind missingKey(String slotName, Slot slot) {
        telemetry.record(slotName, slot.models().getFirst(), 1, "config_error", 0, 0, " reason=missing_api_key");
        return Kind.REJECTED;
    }

    private <T> Chained<T> run(String slotName, Slot slot, Mode mode, Function<String, byte[]> bodyFor,
            String uriTemplate, Answer<T> answer) {
        RestClient client = clients.get(slotName);
        boolean quotaRemembered = false;
        Kind last = Kind.UPSTREAM;
        List<String> models = slot.models();
        for (int index = 0; index < models.size(); index++) {
            String model = models.get(index);
            byte[] body = bodyFor.apply(model);
            int attempts = GeminiChainPolicy.attemptsFor(mode, index, slot.attempts());
            for (int attempt = 1; attempt <= attempts; attempt++) {
                Observation observation = telemetry.start(slotName, model, attempt);
                long started = System.nanoTime();
                Attempt sent;
                try (Observation.Scope ignored = observation.openScope()) {
                    sent = GeminiAttempts.send(client, properties.apiKey(), body, uriTemplate, model);
                }
                Read<T> read = sent.ok() ? answer.read(model, sent.body()) : null;
                Kind failure;
                String outcome;
                String detail;
                if (read != null) {
                    failure = read.failure();
                    outcome = read.outcome();
                    detail = read.detail();
                } else {
                    failure = sent.kind();
                    outcome = sent.interrupted() ? "interrupted" : GeminiAttempts.outcome(failure);
                    detail = sent.error() == null ? "" : " error=" + sent.error();
                }
                telemetry.finish(observation, slotName, model, attempt, outcome, sent.status(), started, detail);
                if (sent.interrupted()) {
                    throw interrupted(model, null);
                }
                if (failure == null) {
                    return new Chained<>(read.result(), null);
                }
                last = failure;
                quotaRemembered |= failure == Kind.QUOTA_EXCEEDED;
                Verdict verdict = GeminiChainPolicy.next(mode, attempt, attempts, failure);
                if (verdict == Verdict.STOP) {
                    return new Chained<>(null, GeminiChainPolicy.finalKind(failure, quotaRemembered));
                }
                if (verdict == Verdict.NEXT_MODEL) {
                    break;
                }
                pause(slot.retryWaits().get(attempt - 1), model);
            }
        }
        return new Chained<>(null, GeminiChainPolicy.finalKind(last, quotaRemembered));
    }

    @FunctionalInterface
    private interface Answer<T> {
        Read<T> read(String model, byte[] body);
    }

    private void pause(Duration wait, String model) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw interrupted(model, ex);
        }
    }

    /** The backend is stopping the call, not the provider failing it, so this carries no provider code. */
    private static IllegalStateException interrupted(String model, InterruptedException cause) {
        return new IllegalStateException("Interrupted while calling " + model, cause);
    }

    private static ObjectNode jsonBody(String userText, String responseSchemaJson) {
        ObjectNode body = GeminiAttempts.JSON.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", userText);
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        if (responseSchemaJson != null) {
            try {
                generation.set("responseSchema", GeminiAttempts.JSON.readTree(responseSchemaJson));
            } catch (JacksonException ex) {
                throw new IllegalArgumentException("responseSchemaJson is not valid JSON", ex);
            }
        }
        return body;
    }

    private static byte[] speechBody(String model, String text, String voice) {
        ObjectNode body = GeminiAttempts.JSON.createObjectNode();
        body.put("model", model);
        body.put("input", text);
        body.putObject("generation_config").putArray("speech_config").addObject().put("voice", voice);
        return GeminiAttempts.JSON.writeValueAsBytes(body);
    }

    /** The first audio part of a 200 answer; an answer without decodable audio is unusable. */
    private static Read<LlmSpeechResult> readSpeech(String model, byte[] answer) {
        JsonNode root;
        try {
            root = GeminiAttempts.JSON.readTree(answer);
        } catch (JacksonException ex) {
            return unusable();
        }
        for (JsonNode step : root.path("steps")) {
            for (JsonNode content : step.path("content")) {
                if (content.path("data").isString()) {
                    try {
                        byte[] wav = Base64.getDecoder().decode(content.path("data").asString());
                        return new Read<>("answered", new LlmSpeechResult.Spoken(wav, model), null, "");
                    } catch (IllegalArgumentException ex) {
                        return unusable();
                    }
                }
            }
        }
        return unusable();
    }

    private static Read<LlmSpeechResult> unusable() {
        return new Read<>("unusable", null, Kind.UNUSABLE, " error=no_audio");
    }
}
