package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowcortex.llm.api.LlmJsonRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class GeminiLlmClientTest {

    private static final String KEY = "test-only-gemini-key";
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String ENDPOINT = "https://llm.test/v1beta/models/" + MODEL + ":generateContent";
    private static final String QUESTION = "How do I reset my password?";
    private static final String SCHEMA = "{\"type\":\"OBJECT\",\"properties\":{\"answer\":{\"type\":\"STRING\"}}}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GeminiLlmClient client = new GeminiLlmClient(properties(KEY), builder.build(), meters);

    private static GeminiProperties properties(String apiKey) {
        return new GeminiProperties(apiKey, URI.create("https://llm.test/v1beta"), Duration.ofSeconds(5),
                Duration.ofSeconds(20));
    }

    private static LlmJsonRequest request() {
        return new LlmJsonRequest(MODEL, "Answer from the articles only.", QUESTION, SCHEMA, 0.2, 512);
    }

    private void geminiAnswers(String body) {
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void anAnswerCarriesTheJsonTheModelVersionAndTheTokenCounts(CapturedOutput output) {
        geminiAnswers("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"{\\"answer\\":\\"Use the link.\\"}"}]},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":14,"thoughtsTokenCount":30},
                 "modelVersion":"gemini-3.5-flash-lite-001"}
                """);

        LlmJsonResult result = client.generateJson(request());

        assertThat(result).isEqualTo(new LlmJsonResult.Answered("{\"answer\":\"Use the link.\"}",
                "gemini-3.5-flash-lite-001", 120, 14));
        assertThat(output).contains("llm.request outcome=answered").doesNotContain(QUESTION).doesNotContain(KEY);
        assertThat(meters.get("llm.requests").tag("outcome", "answered").timer().count()).isEqualTo(1);
        server.verify();
    }

    @Test
    void aSafetyFinishIsBlocked() {
        geminiAnswers("""
                {"candidates":[{"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":40}}
                """);

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Blocked("SAFETY"));
    }

    @Test
    void aBlockedPromptIsBlocked() {
        geminiAnswers("""
                {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":40}}
                """);

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Blocked("PROHIBITED_CONTENT"));
    }

    @Test
    void aReadTimeoutIsATimeout() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT));
    }

    @Test
    void aServerErrorIsAnUpstreamFailure() {
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM));
    }

    @Test
    void aRateLimitIsAnUpstreamFailureWithoutTheConfigErrorLine(CapturedOutput output) {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM));
        assertThat(output).doesNotContain("outcome=config_error");
    }

    @Test
    void aRefusedConnectionIsUnreachable() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new ConnectException("Connection refused")));

        assertThat(client.generateJson(request()))
                .isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.UNREACHABLE));
    }

    @Test
    void aRejectedRequestIsAnUpstreamFailureLoggedAsAConfigError(CapturedOutput output) {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"API key not valid\"}}"));

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM));
        assertThat(output).contains("ERROR").contains("llm.request outcome=config_error status=400 model=" + MODEL)
                .doesNotContain("API key not valid").doesNotContain(KEY).doesNotContain(QUESTION);
    }

    @Test
    void aBlankKeyFailsEveryCallWithoutCallingGemini(CapturedOutput output) {
        GeminiLlmClient unconfigured = new GeminiLlmClient(properties(" "), builder.build(), meters);

        assertThat(unconfigured.generateJson(request()))
                .isEqualTo(new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM));
        assertThat(output).contains("llm.request outcome=config_error");
        server.verify();
    }

    @Test
    void theKeyTravelsInTheHeaderAndTheBodyHoldsOnlyTheInstructionTheTextAndTheConfig() {
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(request -> {
                    assertThat(request.getURI().getRawQuery()).isNull();
                    assertThat(request.getURI().toString()).doesNotContain(KEY);
                    JsonNode body = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body).isEqualTo(JSON.readTree("""
                            {"systemInstruction":{"parts":[{"text":"Answer from the articles only."}]},
                             "contents":[{"role":"user","parts":[{"text":"How do I reset my password?"}]}],
                             "generationConfig":{"temperature":0.2,"maxOutputTokens":512,
                               "responseMimeType":"application/json","responseSchema":%s}}
                            """.formatted(SCHEMA)));
                })
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.generateJson(request())).isEqualTo(new LlmJsonResult.Answered("{}", MODEL, 0, 0));
        server.verify();
    }

    @Test
    void aRequestNeedsAModelATextATokenBudgetOfOneTo8192AndATemperatureOfZeroToTwo() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmJsonRequest(" ", "s", "q", SCHEMA, 0.2, 512));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmJsonRequest(MODEL, "s", "", SCHEMA, 0.2, 512));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmJsonRequest(MODEL, "s", "q", SCHEMA, 0.2, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmJsonRequest(MODEL, "s", "q", SCHEMA, 0.2, 8193));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmJsonRequest(MODEL, "s", "q", SCHEMA, 2.1, 512));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmJsonRequest(MODEL, "s", "q", SCHEMA, -0.1, 512));
        assertThat(new LlmJsonRequest(MODEL, "s", "q", SCHEMA, 2.0, 8192).maxOutputTokens()).isEqualTo(8192);
    }
}
