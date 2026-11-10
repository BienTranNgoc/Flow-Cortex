package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The journey route through the real application: the security rule, the shared rate-limit store, the log
 * stream and the counter. The JDK client is used because it does not sleep on {@code Retry-After}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PaymentJourneyIT extends AbstractWorkflowHttpIT {

    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
    private static final String OPENED = "{\"event\":\"billing.payment_opened\",\"orderRef\":\"" + ORDER_REF
            + "\",\"transactionId\":\"5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11\",\"replayed\":false,\"planId\":\"pro\"}";

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
        telemetryLogger = (Logger) LoggerFactory.getLogger("com.flowcortex.billing.journey.JourneyTelemetry");
        logLines.start();
        telemetryLogger.addAppender(logLines);
    }

    @AfterEach
    void releaseLog() {
        telemetryLogger.detachAppender(logLines);
        logLines.stop();
    }

    @Test
    void anOpenedBodyAnswers204AndWritesOneLineThatNamesNoMember() throws Exception {
        UserView member = newUser("journey-opened");
        String token = loginAs(member);

        HttpResponse<String> response = post(OPENED, "application/json", token);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(lines()).hasSize(1);
        ILoggingEvent line = lines().getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage())
                .contains("event=payment.journey.opened", "order_ref=" + ORDER_REF, "plan_id=pro", "replayed=false")
                .doesNotContain(member.email()).doesNotContain(member.id().toString());
    }

    @Test
    void anUndeclaredKeyIsRefusedInTheRequestLanguageCountedOnceAndWritesNoBodyValue() throws Exception {
        String token = loginAs(newUser("journey-refused"));
        double before = refusals();

        HttpResponse<String> response = post(OPENED.replace("}", ",\"userId\":\"424242\"}"), "application/json",
                token, "Accept-Language", "vi");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("journey_event_invalid");
        assertThat(JSON.readTree(response.body()).get("detail").asText())
                .isEqualTo("Đây không phải sự kiện hành trình thanh toán mà hệ thống ghi nhận.");
        assertThat(refusals()).isEqualTo(before + 1);
        assertThat(lines()).hasSize(1);
        assertThat(lines().getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(lines().getFirst().getFormattedMessage()).endsWith("event=payment.journey.refused")
                .doesNotContain("424242");
    }

    @Test
    void aValidBodyFollowedByTextIsRefused() throws Exception {
        String token = loginAs(newUser("journey-trailing"));

        assertThat(post(OPENED + "x", "application/json", token).statusCode()).isEqualTo(400);
    }

    @Test
    void noTokenIsRefusedWith401AndANonJsonBodyWith415WithoutCountingEither() throws Exception {
        String token = loginAs(newUser("journey-status"));
        double before = refusals();

        assertThat(post(OPENED, "application/json", null).statusCode()).isEqualTo(401);
        assertThat(post(OPENED, "text/plain", token).statusCode()).isEqualTo(415);

        assertThat(refusals()).isEqualTo(before);
        assertThat(lines()).isEmpty();
    }

    @Test
    void theThirtyFirstPostInAMinuteIsRateLimitedFromTheSharedStoreAndNotCountedAsARefusal() throws Exception {
        UserView member = newUser("journey-limit");
        String token = loginAs(member);
        double before = refusals();
        for (int i = 0; i < 30; i++) {
            assertThat(post(OPENED, "application/json", token).statusCode()).isEqualTo(204);
        }

        HttpResponse<String> response = post(OPENED, "application/json", token);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()))
                .isGreaterThanOrEqualTo(1);
        assertThat(refusals()).isEqualTo(before);
        assertThat(meters.get("rate_limit.rejections").tag("limiter", "payment-journey-member").counter().count())
                .isEqualTo(1.0);
        List<String> keys = jdbc.queryForList(
                "SELECT id FROM rate_limit_bucket WHERE id LIKE 'payment-journey-member:%'", String.class);
        assertThat(keys).singleElement().asString().matches("payment-journey-member:[0-9a-f]{64}");
    }

    private double refusals() {
        return meters.counter("payment.journey.refused").count();
    }

    private List<ILoggingEvent> lines() {
        return logLines.list.stream().filter(e -> e.getFormattedMessage().startsWith("Payment journey:")).toList();
    }

    private HttpResponse<String> post(String body, String contentType, String token, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/v1/billing/journey-events"))
                .header("Content-Type", contentType)
                .POST(BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), BodyHandlers.ofString());
    }
}
