package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmJsonRequest;
import com.flowcortex.llm.gemini.GeminiAttempts.Attempt;
import com.flowcortex.llm.registry.ProbeCodes;
import com.flowcortex.llm.registry.ProbeResult;
import com.flowcortex.llm.registry.RegisteredProvider;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Gemini as a registered provider: its model list, a live check that spends no generation quota, and a
 * one-call model test. Logs one line per call with the model, outcome and latency only: never the key, a
 * prompt, an answer or the provider's error body.
 */
final class GeminiRegisteredProvider implements RegisteredProvider {

    static final String KEY = "gemini";

    static final String API_KEY_SETTING = "GEMINI_API_KEY";

    private static final Logger LOG = LoggerFactory.getLogger(GeminiRegisteredProvider.class);

    private static final int LIST_PAGE_SIZE = 1000;

    private static final int CHECK_PAGE_SIZE = 1;

    /** Room for a thinking model's hidden tokens as well as the answer; a smaller cap ends healthy models early. */
    private static final int TEST_MAX_OUTPUT_TOKENS = 256;

    private static final String TEST_PROMPT = "Answer with the JSON object {\"ok\": true}.";

    private static final String TEST_SCHEMA = "{\"type\":\"OBJECT\",\"properties\":{\"ok\":{\"type\":\"BOOLEAN\"}}}";

    private final GeminiProperties properties;

    private final GeminiSlotProperties slotProperties;

    private final RestClient restClient;

    private final LlmClient llmClient;

    GeminiRegisteredProvider(GeminiProperties properties, GeminiSlotProperties slotProperties, RestClient restClient,
            LlmClient llmClient) {
        this.properties = properties;
        this.slotProperties = slotProperties;
        this.restClient = restClient;
        this.llmClient = llmClient;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.TEXT;
    }

    @Override
    public ConfigStatus configStatus() {
        return properties.hasApiKey() ? new ConfigStatus(true, List.of())
                : new ConfigStatus(false, List.of(API_KEY_SETTING));
    }

    @Override
    public boolean liveCheckable() {
        return true;
    }

    @Override
    public ProbeResult liveCheck() {
        if (!properties.hasApiKey()) {
            return missingConfig("-");
        }
        long started = System.nanoTime();
        Attempt attempt = list(CHECK_PAGE_SIZE);
        long latency = millisSince(started);
        ProbeResult result = attempt.ok() ? ProbeResult.reachable(latency)
                : ProbeResult.failed(ProbeCodes.code(attempt.kind()), latency);
        log("-", result);
        return result;
    }

    @Override
    public boolean acceptsModelKey(String modelKey) {
        return GeminiModelList.accepts(modelKey);
    }

    @Override
    public VendorModels listModels() {
        if (!properties.hasApiKey()) {
            missingConfig("-");
            return new VendorModels(List.of(), ProbeCodes.MISSING_CONFIG);
        }
        long started = System.nanoTime();
        Attempt attempt = list(LIST_PAGE_SIZE);
        long latency = millisSince(started);
        VendorModels models = attempt.ok() ? GeminiModelList.parse(attempt.body())
                : new VendorModels(List.of(), ProbeCodes.code(attempt.kind()));
        LOG.info("llm.registry provider={} model=- outcome={} latency_ms={}", KEY,
                models.failure() == null ? "ok" : models.failure(), latency);
        return models;
    }

    @Override
    public ProbeResult testModel(String modelKey) {
        if (!properties.hasApiKey()) {
            return missingConfig(modelKey);
        }
        LlmJsonRequest request = new LlmJsonRequest(modelKey, null, TEST_PROMPT, TEST_SCHEMA, 0.0,
                TEST_MAX_OUTPUT_TOKENS);
        long started = System.nanoTime();
        ProbeResult result = ProbeCodes.of(llmClient.generateJson(request), millisSince(started));
        log(modelKey, result);
        return result;
    }

    @Override
    public List<SlotView> slots() {
        return slotProperties.slots().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey()))
                .map(entry -> new SlotView(entry.getKey(), entry.getValue().models()))
                .toList();
    }

    private Attempt list(int pageSize) {
        return GeminiAttempts.get(restClient, properties.apiKey(), properties.endpoint() + "/models?pageSize={size}",
                pageSize);
    }

    private ProbeResult missingConfig(String model) {
        ProbeResult result = ProbeResult.missingConfig(configStatus().missingSettings());
        log(model, result);
        return result;
    }

    private static void log(String model, ProbeResult result) {
        LOG.info("llm.registry provider={} model={} outcome={} latency_ms={}", KEY, model,
                result.ok() ? "ok" : result.code(), result.latencyMs());
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
