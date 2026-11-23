package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.HttpConnector;
import com.flowcortex.connector.api.HttpConnectorRequest;
import com.flowcortex.connector.api.HttpConnectorResponse;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code connector.call} — one HTTP request through a connector the run's owner saved. A status below
 * 500 completes the step with an envelope of the answer; 500 and above, and every connector failure,
 * fail it with a stable code. The call is made with the owner read from the run, never from the workflow.
 */
@Component
class ConnectorCallStepExecutor implements StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorCallStepExecutor.class);
    static final String TYPE = "connector.call";
    /** The connector's worst case is about 40 s (two connects, one retry wait, one read); the rest is margin. */
    private static final Duration MAX_RUNTIME = Duration.ofSeconds(60);

    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";
    private static final ObjectMapper JSON_READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpConnector httpConnector;
    private final WorkflowRunRepository runs;
    private final DubProperties dub;
    private final ObjectProvider<StepExecutor> executors;

    ConnectorCallStepExecutor(HttpConnector httpConnector, WorkflowRunRepository runs, DubProperties dub,
            ObjectProvider<StepExecutor> executors) {
        this.httpConnector = httpConnector;
        this.runs = runs;
        this.dub = dub;
        this.executors = executors;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    @Override
    public Pool pool() {
        return Pool.PROVIDER;
    }

    @Override
    public Duration maxRuntime() {
        return MAX_RUNTIME;
    }

    /** The queue wait assumes every queued task runs at most its argument, so it takes the longest step sharing the pool. */
    @Override
    public Duration queueTimeout(Duration lease) {
        return lease.plus(dub.pool().queueWait(longestProviderRuntime(MAX_RUNTIME, executors.stream())));
    }

    static Duration longestProviderRuntime(Duration own, Stream<StepExecutor> executors) {
        return executors.filter(executor -> executor.pool() == Pool.PROVIDER)
                .map(StepExecutor::maxRuntime)
                .reduce(own, (longest, runtime) -> runtime.compareTo(longest) > 0 ? runtime : longest);
    }

    @Override
    public String execute(StepContext context) {
        ConnectorCallConfig.Result parsed = ConnectorCallConfig.parse(context.config());
        if (parsed.config() == null) {
            throw ConnectorCallFailures.invalidConfig();
        }
        ConnectorCallConfig config = parsed.config();
        UUID owner = runs.findById(context.runId()).map(WorkflowRun::getOwnerId)
                .orElseThrow(ConnectorCallFailures::runNotFound);
        Body body = body(config, context.input());
        HttpConnectorRequest request = new HttpConnectorRequest(config.connector(), config.method(),
                config.url().toString(), headers(config.headers(), body), body == null ? null : body.text());
        HttpConnectorResponse response = call(owner, request);
        LOG.info("Connector call {} {} answered {} with {} body characters", config.connector(), config.method(),
                response.status(), response.body() == null ? 0 : response.body().length());
        if (response.status() >= 500) {
            throw ConnectorCallFailures.upstreamStatus(response.status());
        }
        return envelope(response);
    }

    /** The request body as text, and whether that text is a JSON document. */
    private record Body(String text, boolean json) {
    }

    /**
     * The port sends a string as-is, and a JSON document is just its text, so the input goes verbatim and
     * a {@code body} from the config goes as its JSON text (a text value as the text itself).
     */
    private static Body body(ConnectorCallConfig config, String input) {
        if (config.bodyFromInput()) {
            if (input == null || input.isBlank() || "null".equals(input.strip())) {
                throw ConnectorCallFailures.emptyBody();
            }
            return new Body(input, isJson(input));
        }
        JsonNode body = config.body();
        if (body == null) {
            return null;
        }
        return body.isTextual() ? new Body(body.asText(), false) : new Body(body.toString(), true);
    }

    private static boolean isJson(String text) {
        try {
            JSON_READER.readTree(text);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * The port labels any body {@code application/json} when no Content-Type is given, so a text body gets
     * its own label here unless the config chose one.
     */
    private static Map<String, String> headers(Map<String, String> configured, Body body) {
        if (body == null || body.json()
                || configured.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("content-type"))) {
            return configured;
        }
        Map<String, String> headers = new LinkedHashMap<>(configured);
        headers.put("Content-Type", TEXT_CONTENT_TYPE);
        return headers;
    }

    private HttpConnectorResponse call(UUID owner, HttpConnectorRequest request) {
        try {
            return httpConnector.call(owner, request);
        } catch (ConnectorException e) {
            LOG.info("Connector call {} {} refused: {}", request.key(), request.method(), e.reason());
            throw ConnectorCallFailures.of(e, request.key());
        } catch (RuntimeException e) {
            LOG.warn("Connector call {} {} failed unexpectedly: {}", request.key(), request.method(),
                    e.getClass().getName());
            throw ConnectorCallFailures.unexpected();
        }
    }

    private String envelope(HttpConnectorResponse response) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", response.status());
        envelope.put("ok", response.ok());
        envelope.put("contentType", response.contentType());
        envelope.put("body", response.body());
        envelope.put("truncated", response.truncated());
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the connector call output", e);
        }
    }
}
