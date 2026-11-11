package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.provider.ProviderTransportException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MomoTransportTest {

    private static final String PATH = "/v2/gateway/api/create";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int status, String contentType, String body) {
        server.createContext(PATH, exchange -> reply(exchange, status, contentType, body));
    }

    private void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private MomoTransport transport() {
        return transport(Duration.ofSeconds(5));
    }

    private MomoTransport transport(Duration requestTimeout) {
        return MomoTransport.http("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                requestTimeout);
    }

    private static ObjectNode request() {
        return MAPPER.createObjectNode().put("orderId", "fc_secret-order-value");
    }

    private ProviderTransportException failure() {
        return failure(transport());
    }

    private static ProviderTransportException failure(MomoTransport transport) {
        try {
            transport.post(PATH, request());
        } catch (ProviderTransportException e) {
            return e;
        }
        throw new AssertionError("expected an unresolved failure");
    }

    @Test
    void anAnswerWithAResultCodeIsReturnedAndTheBodyWasSent() {
        answer(200, "application/json", "{\"resultCode\":0,\"payUrl\":\"https://pay\"}");

        assertThat(transport().post(PATH, request()).get("payUrl").asString()).isEqualTo("https://pay");
        assertThat(received.get()).contains("fc_secret-order-value");
    }

    @Test
    void anAnswerWithOnlyAnErrorCodeIsReturned() {
        answer(200, "application/json", "{\"errorCode\":1001}");

        assertThat(transport().post(PATH, request()).get("errorCode").intValue()).isEqualTo(1001);
    }

    @Test
    void anErrorStatusWithAResultCodeIsStillAnAnswer() {
        answer(400, "application/json", "{\"resultCode\":13,\"message\":\"bad signature\"}");

        assertThat(transport().post(PATH, request()).get("resultCode").intValue()).isEqualTo(13);
    }

    @Test
    void jsonWithoutACodeIsMalformed() {
        answer(200, "application/json", "{\"foo\":1}");

        ProviderTransportException e = failure();

        assertThat(e.kind()).isEqualTo(ProviderTransportException.Kind.MALFORMED_RESPONSE);
    }

    @Test
    void aGatewayErrorPageIsAnHttpStatusFailure() {
        answer(502, "text/html", "<html>Bad Gateway</html>");

        ProviderTransportException e = failure();

        assertThat(e.kind()).isEqualTo(ProviderTransportException.Kind.HTTP_STATUS);
        assertThat(e.status()).isEqualTo(502);
    }

    @Test
    void aSuccessStatusWithAnHtmlBodyIsMalformed() {
        answer(200, "text/html", "<html>maintenance</html>");

        assertThat(failure().kind()).isEqualTo(ProviderTransportException.Kind.MALFORMED_RESPONSE);
    }

    @Test
    void aRedirectIsNotFollowed() {
        AtomicReference<Boolean> elsewhereHit = new AtomicReference<>(false);
        server.createContext("/elsewhere", exchange -> {
            elsewhereHit.set(true);
            reply(exchange, 200, "application/json", "{\"resultCode\":0}");
        });
        server.createContext(PATH, exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
            reply(exchange, 302, "text/plain", "");
        });

        ProviderTransportException e = failure();

        assertThat(e.kind()).isEqualTo(ProviderTransportException.Kind.HTTP_STATUS);
        assertThat(e.status()).isEqualTo(302);
        assertThat(elsewhereHit.get()).isFalse();
    }

    @Test
    void noAnswerWithinTheTimeoutIsATimeout() {
        server.createContext(PATH, exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        assertThat(failure(transport(Duration.ofMillis(200))).kind())
                .isEqualTo(ProviderTransportException.Kind.TIMEOUT);
    }

    @Test
    void aClosedPortIsANetworkFailure() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        MomoTransport closed = MomoTransport.http("http://127.0.0.1:" + port, Duration.ofSeconds(2),
                Duration.ofMillis(200));

        assertThatThrownBy(() -> closed.post(PATH, request()))
                .isInstanceOfSatisfying(ProviderTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProviderTransportException.Kind.NETWORK));
    }

    @Test
    void failureMessagesNameProviderAndEndpointButNoRequestValue() {
        answer(502, "text/html", "<html>x</html>");

        assertThat(failure().getMessage()).contains("provider=momo").contains("endpoint=" + PATH)
                .doesNotContain("fc_secret-order-value");
    }
}
