package com.flowcortex.app.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** A plain HTTP client for the realtime stream, plus a token minter whose expiry is exact. */
final class SseTestClient {

    static final Duration WAIT = Duration.ofSeconds(15);

    private SseTestClient() {
    }

    /** A token the app's own decoder accepts; {@code expiresAt} may be in the past. */
    static String token(JwtEncoder encoder, UUID subject, Instant expiresAt) {
        Instant issuedAt = expiresAt.isBefore(Instant.now()) ? expiresAt.minusSeconds(1) : Instant.now().minusSeconds(1);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("flow-cortex")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(subject.toString())
                .claim("role", "ADMIN")
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    static Open open(HttpClient http, int port, String query, String token, String lastEventId)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/v1/realtime/stream" + query))
                .header("Accept", "text/event-stream");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return new Open(http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
    }

    /** One response: its status and headers at once, its lines as they arrive. */
    static final class Open {

        private final HttpResponse<InputStream> response;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> seen = new CopyOnWriteArrayList<>();
        private volatile boolean ended;

        Open(HttpResponse<InputStream> response) {
            this.response = response;
            Thread reader = new Thread(this::read, "it-sse-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void read() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String field = withSingleSpace(line);
                    seen.add(field);
                    lines.add(field);
                }
            } catch (IOException closedByTest) {
                // The test closes the stream when it is done with it.
            } finally {
                ended = true;
            }
        }

        /** Spring writes {@code id:x}; the space after the colon is optional on the wire, so normalize it. */
        private static String withSingleSpace(String line) {
            int colon = line.indexOf(':');
            if (colon < 0 || line.startsWith("{")) {
                return line;
            }
            return line.substring(0, colon) + ": " + line.substring(colon + 1).stripLeading();
        }

        int status() {
            return response.statusCode();
        }

        String header(String name) {
            return response.headers().firstValue(name).orElse("");
        }

        /** The whole body of a response that is not a stream. */
        String body() {
            Awaitility.await().atMost(WAIT).until(() -> ended);
            return String.join("\n", seen);
        }

        List<String> seen() {
            return seen;
        }

        long count(Predicate<String> wanted) {
            return seen.stream().filter(wanted).count();
        }

        void await(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(200, TimeUnit.MILLISECONDS);
                if (line != null && wanted.test(line)) {
                    return;
                }
            }
            assertThat(seen).as("a wanted line within " + WAIT).anyMatch(wanted);
        }

        void awaitEnd() {
            Awaitility.await().atMost(WAIT).until(() -> ended);
        }

        void close() {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
