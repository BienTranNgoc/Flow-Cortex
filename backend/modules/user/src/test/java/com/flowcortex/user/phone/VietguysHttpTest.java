package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.VietguysHttp.Answer;
import com.flowcortex.user.phone.VietguysHttp.NotSent;
import com.flowcortex.user.phone.VietguysHttp.Outcome;
import com.flowcortex.user.phone.VietguysHttp.Unconfirmed;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Local sockets only: no Docker, no network. The proxy is explicit, so {@code http.nonProxyHosts} cannot bypass it. */
class VietguysHttpTest {

    private static final byte[] BODY = "u=demo&sms=hello".getBytes(StandardCharsets.UTF_8);
    private static final URI HTTPS_TARGET = URI.create("https://sms.example.test:4438/api/index.php");

    private final List<ServerSocket> servers = new ArrayList<>();
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeEverything() throws IOException {
        for (Socket socket : accepted) {
            socket.close();
        }
        for (ServerSocket server : servers) {
            server.close();
        }
    }

    @Test
    void aProxyThatRefusesTheTunnelWith407IsNotSent() throws IOException {
        int proxy = serve(socket -> {
            readRequest(socket);
            respond(socket, "407 Proxy Authentication Required", "");
        });

        Outcome outcome = post(HTTPS_TARGET, throughProxy(proxy));

        assertThat(outcome).isInstanceOfSatisfying(NotSent.class,
                notSent -> assertThat(notSent.reason()).isEqualTo("java.io.IOException"));
    }

    @Test
    void aProxyThatCannotReachTheHostWith502IsNotSent() throws IOException {
        int proxy = serve(socket -> {
            readRequest(socket);
            respond(socket, "502 Bad Gateway", "");
        });

        assertThat(post(HTTPS_TARGET, throughProxy(proxy))).isInstanceOf(NotSent.class);
    }

    @Test
    void aProxyThatTimesOutDialingWith504IsNotSent() throws IOException {
        int proxy = serve(socket -> {
            readRequest(socket);
            respond(socket, "504 Gateway Timeout", "");
        });

        assertThat(post(HTTPS_TARGET, throughProxy(proxy))).isInstanceOf(NotSent.class);
    }

    @Test
    void aTunnelThatOpensToAFarSideSpeakingPlainTextFailsTheHandshakeAndIsNotSent() throws IOException {
        int proxy = serve(socket -> {
            readRequest(socket);
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            socket.getInputStream().read();
        });

        assertThat(post(HTTPS_TARGET, throughProxy(proxy))).isInstanceOfSatisfying(NotSent.class,
                notSent -> assertThat(notSent.reason()).startsWith("javax.net.ssl.SSL"));
    }

    @Test
    void aHostThatDoesNotResolveIsNotSentAndTheReasonIsTheClassNameOnly() {
        Outcome outcome = post(URI.create("https://no-such-host.invalid/api"), direct());

        assertThat(outcome).isInstanceOfSatisfying(NotSent.class,
                notSent -> assertThat(notSent.reason()).isEqualTo("java.net.UnknownHostException"));
    }

    @Test
    void aProxyWithNothingListeningIsNotSent() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        assertThat(post(HTTPS_TARGET, throughProxy(closedPort))).isInstanceOf(NotSent.class);
    }

    @Test
    void aRequestThatIsWrittenAndNeverAnsweredIsUnconfirmedAfterTheReadTimeout() throws IOException {
        int target = serve(socket -> {
            readRequest(socket);
            socket.getInputStream().read();
        });

        Outcome outcome = post(URI.create("http://127.0.0.1:" + target + "/api"), direct());

        assertThat(outcome).isInstanceOfSatisfying(Unconfirmed.class,
                unconfirmed -> assertThat(unconfirmed.reason()).isEqualTo("java.net.SocketTimeoutException"));
    }

    @Test
    void aRequestThatIsWrittenAndThenTheConnectionClosedIsUnconfirmed() throws IOException {
        int target = serve(VietguysHttpTest::readRequest);

        assertThat(post(URI.create("http://127.0.0.1:" + target + "/api"), direct())).isInstanceOf(Unconfirmed.class);
    }

    @Test
    void anErrorPageComesBackAsAnAnswerWithItsStatusAndBytes() throws IOException {
        int target = serve(socket -> {
            readRequest(socket);
            respond(socket, "502 Bad Gateway", "<html>bad gateway</html>");
        });

        Outcome outcome = post(URI.create("http://127.0.0.1:" + target + "/api"), direct());

        assertThat(outcome).isInstanceOfSatisfying(Answer.class, answer -> {
            assertThat(answer.status()).isEqualTo(502);
            assertThat(answer.body()).isEqualTo("<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void anAcceptedAnswerComesBackAfterExactlyOneFixedLengthPost() throws IOException {
        List<Request> received = new CopyOnWriteArrayList<>();
        int target = serve(socket -> {
            received.add(readRequest(socket));
            respond(socket, "200 OK", "{\"error\":0}");
        });

        Outcome outcome = new VietguysHttp(Duration.ofSeconds(2), Duration.ofSeconds(1), direct()).post(
                URI.create("http://127.0.0.1:" + target + "/api/index.php"),
                Map.of("Access-Token", "token-value"), "multipart/form-data; boundary=xyz", BODY);

        assertThat(outcome).isInstanceOfSatisfying(Answer.class, answer -> {
            assertThat(answer.status()).isEqualTo(200);
            assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":0}");
        });
        assertThat(received).hasSize(1);
        Request request = received.get(0);
        assertThat(request.head()).startsWith("POST /api/index.php HTTP/1.1");
        assertThat(request.head().toLowerCase()).contains("content-length: " + BODY.length)
                .contains("access-token: token-value")
                .contains("content-type: multipart/form-data; boundary=xyz")
                .doesNotContain("transfer-encoding");
        assertThat(request.body()).isEqualTo(BODY);
    }

    @Test
    void a401AnswerComesBackAsAnAnswerWithItsStatusAndNoRetry() throws IOException {
        List<Request> received = new CopyOnWriteArrayList<>();
        int target = serve(socket -> {
            received.add(readRequest(socket));
            respond(socket, "401 Unauthorized", "{\"error\":1,\"error_code\":-5}");
        });

        Outcome outcome = post(URI.create("http://127.0.0.1:" + target + "/api"), direct());

        assertThat(outcome).isInstanceOfSatisfying(Answer.class, answer -> assertThat(answer.status()).isEqualTo(401));
        assertThat(received).hasSize(1);
    }

    @Test
    void aRedirectIsNotFollowedSoTheTokenStaysWithTheHostItWasMeantFor() throws IOException {
        List<Request> received = new CopyOnWriteArrayList<>();
        int target = serve(socket -> {
            received.add(readRequest(socket));
            respond(socket, "302 Found", "", "Location: http://127.0.0.1:1/elsewhere");
        });

        Outcome outcome = post(URI.create("http://127.0.0.1:" + target + "/api"), direct());

        assertThat(outcome).isInstanceOfSatisfying(Answer.class, answer -> assertThat(answer.status()).isEqualTo(302));
        assertThat(received).hasSize(1);
    }

    @Test
    void aHugeAnswerBodyIsCappedAt64KiB() throws IOException {
        int target = serve(socket -> {
            readRequest(socket);
            respond(socket, "200 OK", "x".repeat(100 * 1024));
        });

        Outcome outcome = post(URI.create("http://127.0.0.1:" + target + "/api"), direct());

        assertThat(outcome).isInstanceOfSatisfying(Answer.class,
                answer -> assertThat(answer.body()).hasSize(64 * 1024));
    }

    @Test
    void aHeaderValueThatCannotBeSentIsNotSent() throws IOException {
        int target = serve(socket -> respond(socket, "200 OK", "{\"error\":0}"));

        Outcome outcome = new VietguysHttp(Duration.ofSeconds(2), Duration.ofSeconds(1), direct()).post(
                URI.create("http://127.0.0.1:" + target + "/api"), Map.of("Access-Token", "bad\r\nInjected: 1"),
                "text/plain", BODY);

        assertThat(outcome).isInstanceOf(NotSent.class);
    }

    private static Outcome post(URI url, ProxySelector proxies) {
        return new VietguysHttp(Duration.ofSeconds(2), Duration.ofSeconds(1), proxies)
                .post(url, Map.of("Access-Token", "token-value"), "application/x-www-form-urlencoded", BODY);
    }

    private static ProxySelector throughProxy(int port) {
        return ProxySelector.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static ProxySelector direct() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            }
        };
    }

    private int serve(SocketHandler handler) throws IOException {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        servers.add(server);
        Thread thread = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    Socket socket = server.accept();
                    accepted.add(socket);
                    try {
                        handler.handle(socket);
                    } catch (IOException ignored) {
                        // a client that gave up closes its end mid-write
                    } finally {
                        if (!socket.isClosed()) {
                            socket.close();
                        }
                    }
                }
            } catch (IOException ignored) {
                // the server socket was closed by the test
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    private static Request readRequest(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            head.write(next);
            matched = next == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        int length = 0;
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = in.readNBytes(length);
        return new Request(text, body);
    }

    private static void respond(Socket socket, String status, String body, String... extraHeaders) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Connection: close\r\n");
        for (String header : extraHeaders) {
            head.append(header).append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private record Request(String head, byte[] body) {
    }

    @FunctionalInterface
    private interface SocketHandler {
        void handle(Socket socket) throws IOException;
    }
}
