package com.flowcortex.app.clienterrors;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.AbstractPostgresBootTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The public intake end to end: the JDK client is used because it does not sleep on {@code Retry-After},
 * and every address is {@code 127.0.0.1}, so each test starts from empty buckets.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ClientErrorsIT extends AbstractPostgresBootTest {

    private static final String VALID = "{\"v\":1,\"kind\":\"render\",\"app\":\"member\","
            + "\"name\":\"TypeError\",\"route\":\"/workflows/42\"}";
    private static final String EXPIRED_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4IiwiZXhwIjoxfQ.c2lnbmF0dXJl";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();
    private Logger telemetryLogger;

    @BeforeEach
    void captureLog() {
        telemetryLogger = (Logger) LoggerFactory.getLogger(ClientErrorTelemetry.class);
        logLines.start();
        telemetryLogger.addAppender(logLines);
    }

    @AfterEach
    void releaseLog() {
        telemetryLogger.detachAppender(logLines);
        logLines.stop();
    }

    @Test
    void anAnonymousValidReportAnswers204() throws Exception {
        HttpResponse<String> response = post(VALID, "text/plain;charset=UTF-8");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void anExpiredBearerTokenDoesNotChangeTheAnswer() throws Exception {
        HttpResponse<String> response = post(VALID, "text/plain", "Authorization", "Bearer " + EXPIRED_TOKEN);

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @Test
    void aJsonMediaTypeIsRefusedByClass() throws Exception {
        HttpResponse<String> response = post(VALID, "application/json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("client_error_content_type");
    }

    @Test
    void aBodyOfExactlyTheLimitIsJudgedOnItsContentAndOneByteMoreIsTooLarge() throws Exception {
        String atLimit = VALID + " ".repeat(8192 - VALID.length());
        String overLimit = atLimit + " ";

        HttpResponse<String> judged = post(atLimit, "text/plain");
        HttpResponse<String> tooLarge = post(overLimit, "text/plain");

        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(8192);
        assertThat(judged.statusCode()).isEqualTo(204);
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(tooLarge.body()).get("code").asText()).isEqualTo("client_error_too_large");
    }

    @Test
    void anUnreadableBodyAndAnInvalidOneAreRefusedByClass() throws Exception {
        HttpResponse<String> unreadable = post("not json", "text/plain");
        HttpResponse<String> invalid = post(VALID.replace("\"v\":1", "\"v\":2"), "text/plain");

        assertThat(unreadable.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(unreadable.body()).get("code").asText()).isEqualTo("client_error_unreadable");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(invalid.body()).get("code").asText()).isEqualTo("client_error_invalid");
    }

    @Test
    void aRefusalNeverEchoesWhatTheCallerSent() throws Exception {
        String body = "{\"v\":1,\"kind\":\"render\",\"app\":\"member\",\"name\":\"<script>alert(1)</script>\","
                + "\"route\":\"/a\",\"x\":1}";

        HttpResponse<String> response = post(body, "text/plain");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("client_error_invalid");
        assertThat(response.body()).doesNotContain("<script>").doesNotContain("\"x\"").doesNotContain("alert");
    }

    @Test
    void theEleventhReportInAMinuteIsRateLimitedWithRetryAfter() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post(VALID, "text/plain").statusCode()).isEqualTo(204);
        }

        HttpResponse<String> eleventh = post(VALID, "text/plain");

        assertThat(eleventh.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(eleventh.headers().firstValue("Retry-After").orElseThrow())).isGreaterThanOrEqualTo(1);
        assertThat(JSON.readTree(eleventh.body()).get("code").asText()).isEqualTo("client_error_rate_limited");
    }

    @Test
    void anAcceptedReportWritesOneRedactedLineAndCountsOnce() throws Exception {
        double before = counter("render", "member");

        String report = "{\"v\":1,\"kind\":\"render\",\"app\":\"member\",\"name\":\"Error an@example.com\","
                + "\"route\":\"/workflows/42?tab=1\",\"lang\":\"vi\"}";
        HttpResponse<String> response = post(report, "text/plain");

        assertThat(response.statusCode()).isEqualTo(204);
        List<ILoggingEvent> lines = clientErrorLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getLevel()).isEqualTo(Level.WARN);
        String line = lines.getFirst().getFormattedMessage();
        assertThat(line).contains("kind=render", "app=member", "name=Error <redacted>", "route=/workflows/:id", "lang=vi")
                .doesNotContain("127.0.0.1").doesNotContain("an@example.com");
        assertThat(counter("render", "member")).isEqualTo(before + 1);
    }

    @Test
    void aRefusedReportRecordsNothing() throws Exception {
        double before = counter("render", "member");

        post(VALID.replace("\"v\":1", "\"v\":2"), "text/plain");

        assertThat(clientErrorLines()).isEmpty();
        assertThat(counter("render", "member")).isEqualTo(before);
    }

    @Test
    void everyRefusalClassAnswersInTheRequestLanguage() throws Exception {
        record Case(String code, String english, String vietnamese) { }

        List<Case> cases = List.of(
                new Case("client_error_content_type", "Client error reports are sent as text/plain.",
                        "Báo cáo lỗi phải gửi dạng text/plain."),
                new Case("client_error_unreadable", "The client error report is not JSON.",
                        "Báo cáo lỗi không phải JSON."),
                new Case("client_error_invalid", "The client error report does not match the schema.",
                        "Báo cáo lỗi không đúng định dạng."),
                new Case("client_error_too_large", "The client error report is larger than 8 KB.",
                        "Báo cáo lỗi vượt quá 8 KB."),
                new Case("client_error_rate_limited", "Too many client error reports; try again later.",
                        "Có quá nhiều báo cáo lỗi. Hãy thử lại sau."));

        for (String language : List.of("en", "vi")) {
            for (Case expected : cases) {
                jdbc.update("DELETE FROM rate_limit_bucket");
                HttpResponse<String> response = refusal(expected.code(), language);
                JsonNode problem = JSON.readTree(response.body());
                assertThat(problem.get("code").asText()).isEqualTo(expected.code());
                assertThat(problem.get("detail").asText())
                        .as("%s in %s", expected.code(), language)
                        .isEqualTo(language.equals("en") ? expected.english() : expected.vietnamese());
            }
        }
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.client-errors.enabled=false")
    class SwitchedOff {

        @Autowired
        private MeterRegistry nestedMeters;

        @Autowired
        private JdbcTemplate nestedJdbc;

        @LocalServerPort
        private int nestedPort;

        @Test
        void anInvalidBodyAnswers204WithoutALineACounterOrALimiterRow() throws Exception {
            double before = nestedMeters.counter("client_errors", "kind", "render", "app", "member").count();

            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + nestedPort + "/api/v1/client-errors"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("not json")).build(), BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(clientErrorLines()).isEmpty();
            assertThat(nestedMeters.counter("client_errors", "kind", "render", "app", "member").count()).isEqualTo(before);
            assertThat(nestedJdbc.queryForObject("SELECT count(*) FROM rate_limit_bucket", Integer.class)).isZero();
        }
    }

    private HttpResponse<String> refusal(String code, String language) throws Exception {
        return switch (code) {
            case "client_error_content_type" -> post(VALID, "application/json", "Accept-Language", language);
            case "client_error_unreadable" -> post("not json", "text/plain", "Accept-Language", language);
            case "client_error_invalid" -> post("{}", "text/plain", "Accept-Language", language);
            case "client_error_too_large" -> post(" ".repeat(8193), "text/plain", "Accept-Language", language);
            default -> {
                for (int i = 0; i < 10; i++) {
                    post(VALID, "text/plain");
                }
                yield post(VALID, "text/plain", "Accept-Language", language);
            }
        };
    }

    private List<ILoggingEvent> clientErrorLines() {
        return logLines.list.stream().filter(e -> e.getFormattedMessage().startsWith("client_error ")).toList();
    }

    private double counter(String kind, String app) {
        return meters.counter("client_errors", "kind", kind, "app", app).count();
    }

    private HttpResponse<String> post(String body, String contentType, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/client-errors"))
                .header("Content-Type", contentType)
                .POST(BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), BodyHandlers.ofString());
    }
}
