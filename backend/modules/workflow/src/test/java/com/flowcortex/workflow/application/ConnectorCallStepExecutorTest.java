package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.dub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.HttpConnector;
import com.flowcortex.connector.api.HttpConnectorRequest;
import com.flowcortex.connector.api.HttpConnectorResponse;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** {@code connector.call}: the run owner's connector, the config's request, the envelope, one code per failure. */
class ConnectorCallStepExecutorTest {

    private static final String GET_CONFIG = "{\"connector\":\"notion-main\",\"url\":\"https://api.notion.com/v1/users\"}";
    private static final String POST_CONFIG = "{\"connector\":\"notion-main\",\"method\":\"POST\","
            + "\"url\":\"https://api.notion.com/v1/pages\",\"headers\":{\"Notion-Version\":\"2022-06-28\"},"
            + "\"bodyFromInput\":true}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpConnector connector = mock(HttpConnector.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StepExecutor> others = mock(ObjectProvider.class);

    private ConnectorCallStepExecutor executor() {
        when(others.stream()).thenAnswer(call -> Stream.<StepExecutor>empty());
        return new ConnectorCallStepExecutor(connector, runs, dub(), others);
    }

    private StepContext context(String config, String input) {
        return new StepContext(runId, UUID.randomUUID(), 2, "connector.call", (short) 1, input, config, null);
    }

    private void runStartedByOwner() {
        when(runs.findById(runId)).thenReturn(
                Optional.of(new WorkflowRun(runId, UUID.randomUUID(), owner, null, Instant.now())));
    }

    private static HttpConnectorResponse answer(int status, String contentType, String body) {
        return new HttpConnectorResponse(status, status >= 200 && status < 300, contentType, body, false);
    }

    private HttpConnectorRequest sent() {
        ArgumentCaptor<HttpConnectorRequest> request = ArgumentCaptor.forClass(HttpConnectorRequest.class);
        verify(connector).call(eq(owner), request.capture());
        return request.getValue();
    }

    private static StepFailedException failureOf(Runnable step) {
        return org.junit.jupiter.api.Assertions.assertThrows(StepFailedException.class, step::run);
    }

    @Test
    void theCallIsMadeForTheRunOwnerWithTheConfigsRequest() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "application/json", "{}"));

        executor().execute(context(POST_CONFIG, "{\"title\":\"Launch\"}"));

        HttpConnectorRequest request = sent();
        assertThat(request.key()).isEqualTo("notion-main");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.url()).isEqualTo("https://api.notion.com/v1/pages");
        assertThat(request.headers()).containsExactly(Map.entry("Notion-Version", "2022-06-28"));
    }

    @Test
    void aJsonInputIsSentAsTheJsonObject() throws Exception {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "application/json", "{}"));

        executor().execute(context(POST_CONFIG, "{\"title\":\"Launch\"}"));

        JsonNode body = json.readTree((String) sent().body());
        assertThat(body.isObject()).isTrue();
        assertThat(body.path("title").asText()).isEqualTo("Launch");
        assertThat(sent().headers()).doesNotContainKey("Content-Type");
    }

    @Test
    void aTextInputIsSentAsTextLabelledAsText() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "text/plain", "ok"));

        executor().execute(context(POST_CONFIG, "Xin chào"));

        HttpConnectorRequest request = sent();
        assertThat(request.body()).isEqualTo("Xin chào");
        assertThat(request.headers()).containsExactly(Map.entry("Notion-Version", "2022-06-28"),
                Map.entry("Content-Type", "text/plain; charset=utf-8"));
    }

    @Test
    void textThatOnlyStartsWithJsonIsLabelledAsText() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "text/plain", "ok"));

        executor().execute(context(POST_CONFIG, "{\"a\":1} and more"));

        assertThat(sent().headers()).containsEntry("Content-Type", "text/plain; charset=utf-8");
    }

    @Test
    void aTextInputKeepsAContentTypeTheConfigGave() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "text/plain", "ok"));
        String withType = "{\"connector\":\"notion-main\",\"method\":\"POST\",\"url\":\"https://api.notion.com/v1/x\","
                + "\"headers\":{\"content-type\":\"text/csv\"},\"bodyFromInput\":true}";

        executor().execute(context(withType, "a,b"));

        assertThat(sent().headers()).containsExactly(Map.entry("content-type", "text/csv"));
    }

    @Test
    void theSameStepIsLabelledPerRunByWhatItReceives() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "text/plain", "ok"));
        ConnectorCallStepExecutor executor = executor();

        executor.execute(context(POST_CONFIG, "{\"title\":\"Launch\"}"));
        executor.execute(context(POST_CONFIG, "Xin chào"));

        ArgumentCaptor<HttpConnectorRequest> requests = ArgumentCaptor.forClass(HttpConnectorRequest.class);
        verify(connector, org.mockito.Mockito.times(2)).call(eq(owner), requests.capture());
        assertThat(requests.getAllValues().get(0).headers()).doesNotContainKey("Content-Type");
        assertThat(requests.getAllValues().get(1).headers()).containsEntry("Content-Type",
                "text/plain; charset=utf-8");
    }

    @Test
    void anInputBodyWithNoUsableInputFailsWithoutACall() {
        runStartedByOwner();
        ConnectorCallStepExecutor executor = executor();

        for (String input : new String[] {null, "", "  ", "null"}) {
            StepFailedException failure = failureOf(() -> executor.execute(context(POST_CONFIG, input)));
            assertThat(failure.code()).as("input %s", input).isEqualTo("connector_invalid_request");
        }
        verify(connector, never()).call(any(), any());
    }

    @Test
    void aConfigBodyIsSentAsGivenAndNoBodySourceSendsNone() throws Exception {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "application/json", "{}"));
        String withBody = "{\"connector\":\"notion-main\",\"method\":\"PUT\",\"url\":\"https://api.notion.com/v1/x\","
                + "\"body\":{\"a\":[1,2],\"b\":\"c\"}}";

        executor().execute(context(withBody, "ignored"));

        HttpConnectorRequest request = sent();
        assertThat(json.readTree((String) request.body())).isEqualTo(json.readTree("{\"a\":[1,2],\"b\":\"c\"}"));
        assertThat(request.headers()).isEmpty();
    }

    @Test
    void aTextConfigBodyIsSentAsItsText() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "application/json", "{}"));
        String withText = "{\"connector\":\"notion-main\",\"method\":\"POST\",\"url\":\"https://api.notion.com/v1/x\","
                + "\"body\":\"plain words\"}";

        executor().execute(context(withText, null));

        HttpConnectorRequest request = sent();
        assertThat(request.body()).isEqualTo("plain words");
        assertThat(request.headers()).containsExactly(Map.entry("Content-Type", "text/plain; charset=utf-8"));
    }

    @Test
    void withoutABodySourceTheBodyIsNull() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "application/json", "{}"));

        executor().execute(context(GET_CONFIG, "previous output"));

        assertThat(sent().body()).isNull();
    }

    @Test
    void aSuccessAnswerIsTheEnvelope() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(200, "application/json", "{\"results\":[]}"));

        String output = executor().execute(context(GET_CONFIG, null));

        assertThat(output).isEqualTo("{\"status\":200,\"ok\":true,\"contentType\":\"application/json\","
                + "\"body\":\"{\\\"results\\\":[]}\",\"truncated\":false}");
    }

    @Test
    void aTruncatedAnswerSaysSo() throws Exception {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(new HttpConnectorResponse(200, true, "text/plain", "x", true));

        JsonNode output = json.readTree(executor().execute(context(GET_CONFIG, null)));

        assertThat(output.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void aClientErrorAndARedirectCompleteTheStepAsData() throws Exception {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(404, "text/plain", "not found"),
                answer(302, null, ""));
        ConnectorCallStepExecutor executor = executor();

        JsonNode notFound = json.readTree(executor.execute(context(GET_CONFIG, null)));
        JsonNode redirect = json.readTree(executor.execute(context(GET_CONFIG, null)));

        assertThat(notFound.path("status").asInt()).isEqualTo(404);
        assertThat(notFound.path("ok").asBoolean()).isFalse();
        assertThat(redirect.path("status").asInt()).isEqualTo(302);
    }

    @Test
    void aServerErrorFailsTheStepWithoutPassingTheBodyOn() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenReturn(answer(500, "text/html", "<html>oops</html>"),
                answer(503, "text/html", "<html>down</html>"));
        ConnectorCallStepExecutor executor = executor();

        StepFailedException first = failureOf(() -> executor.execute(context(GET_CONFIG, null)));
        StepFailedException second = failureOf(() -> executor.execute(context(GET_CONFIG, null)));

        assertThat(first.code()).isEqualTo("connector_upstream_error");
        assertThat(first.getMessage()).isEqualTo("The connected service answered HTTP 500.");
        assertThat(second.getMessage()).isEqualTo("The connected service answered HTTP 503.")
                .doesNotContain("html");
    }

    @Test
    void aConnectorFailureEndsTheStepWithItsCode() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenThrow(ConnectorException.of(ConnectorException.Reason.NOT_CONNECTED));

        StepFailedException failure = failureOf(() -> executor().execute(context(GET_CONFIG, null)));

        assertThat(failure.code()).isEqualTo("connector_not_connected");
        assertThat(failure.getMessage()).contains("notion-main");
    }

    @Test
    void anUnexpectedFailureOfTheCallLeaksNoText() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenThrow(new IllegalStateException("member 7f3a not found"));

        StepFailedException failure = failureOf(() -> executor().execute(context(GET_CONFIG, null)));

        assertThat(failure.code()).isEqualTo("connector_upstream_unavailable");
        assertThat(failure.getMessage()).doesNotContain("7f3a");
    }

    @Test
    void aConnectorFailureWithATokenInItsDetailLeaksNothing() {
        runStartedByOwner();
        when(connector.call(any(), any())).thenThrow(new ConnectorException(ConnectorException.Reason.REFUSED,
                new LocalizedMessage("https://api.example.com/x?api_key=tok-123")));

        StepFailedException failure = failureOf(() -> executor().execute(context(GET_CONFIG, null)));

        assertThat(failure.code()).isEqualTo("connector_refused");
        assertThat(failure.getMessage()).doesNotContain("tok-123").doesNotContain("https://");
    }

    @Test
    void aConfigThatNoLongerParsesFailsWithoutACall() {
        runStartedByOwner();

        for (String config : new String[] {null, "{", "{\"connector\":\"notion-main\"}"}) {
            StepFailedException failure = failureOf(() -> executor().execute(context(config, null)));
            assertThat(failure.code()).as("config %s", config).isEqualTo("connector_invalid_request");
        }
        verify(connector, never()).call(any(), any());
    }

    @Test
    void aMissingRunFailsWithoutACall() {
        when(runs.findById(runId)).thenReturn(Optional.empty());

        StepFailedException failure = failureOf(() -> executor().execute(context(GET_CONFIG, null)));

        assertThat(failure.code()).isEqualTo("connector_invalid_request");
        verify(connector, never()).call(any(), any());
    }

    @Test
    void theStepRunsInlineOnTheProviderThreadsWithinAMinute() {
        ConnectorCallStepExecutor executor = executor();

        assertThat(executor.type()).isEqualTo("connector.call");
        assertThat(executor.mode()).isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(executor.pool()).isEqualTo(StepExecutor.Pool.PROVIDER);
        assertThat(executor.maxRuntime()).isEqualTo(Duration.ofSeconds(60));
        assertThat(executor.available()).isTrue();
    }

    @Test
    void theQueueWaitFollowsTheLongestProviderStep() {
        StepExecutor longestProvider = executorOf(StepExecutor.Pool.PROVIDER, Duration.ofMinutes(10));
        StepExecutor shared = executorOf(StepExecutor.Pool.SHARED, Duration.ofMinutes(20));
        when(others.stream()).thenAnswer(call -> Stream.of(longestProvider, shared));
        ConnectorCallStepExecutor executor = new ConnectorCallStepExecutor(connector, runs, dub(), others);
        Duration lease = Duration.ofMinutes(2);

        assertThat(executor.queueTimeout(lease)).isEqualTo(lease.plus(dub().pool().queueWait(Duration.ofMinutes(10))));
    }

    @Test
    void withNoOtherProviderStepTheQueueWaitFollowsItsOwnMinute() {
        ConnectorCallStepExecutor executor = executor();
        when(others.stream()).thenAnswer(call -> Stream.of(executor));
        Duration lease = Duration.ofMinutes(2);

        assertThat(executor.queueTimeout(lease)).isEqualTo(lease.plus(dub().pool().queueWait(Duration.ofSeconds(60))));
    }

    @Test
    void theLongestProviderRuntimeIgnoresSharedStepsAndKeepsItsOwnFloor() {
        assertThat(ConnectorCallStepExecutor.longestProviderRuntime(Duration.ofSeconds(60),
                Stream.of(executorOf(StepExecutor.Pool.SHARED, Duration.ofMinutes(20)),
                        executorOf(StepExecutor.Pool.PROVIDER, Duration.ofSeconds(5)))))
                .isEqualTo(Duration.ofSeconds(60));
    }

    private static StepExecutor executorOf(StepExecutor.Pool pool, Duration maxRuntime) {
        StepExecutor executor = mock(StepExecutor.class);
        when(executor.pool()).thenReturn(pool);
        when(executor.maxRuntime()).thenReturn(maxRuntime);
        return executor;
    }
}
