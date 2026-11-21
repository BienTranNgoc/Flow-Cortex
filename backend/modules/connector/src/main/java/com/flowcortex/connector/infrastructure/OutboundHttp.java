package com.flowcortex.connector.infrastructure;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.application.OutboundFailurePhase;
import com.flowcortex.connector.application.OutboundRetryPolicy;
import com.flowcortex.shared.web.LocalizedMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client every connector uses. It never follows a redirect, reads every status as a value,
 * caps the response body, retries only a request that was never written, and reports every failure as
 * a {@link ConnectorException} that carries neither the URL (a Telegram token sits in its path, an
 * HTTP connector token may sit in its query) nor the client's own message.
 */
@Component
public class OutboundHttp {

    /** Bytes read from a response: 100,000 characters at up to 4 UTF-8 bytes each. */
    static final int MAX_BODY_BYTES = 400_000;
    /** Characters of a response body handed to the caller. */
    static final int MAX_BODY_CHARS = 100_000;

    private static final Logger LOG = LoggerFactory.getLogger(OutboundHttp.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_PAUSE = Duration.ofMillis(250);

    /** One outbound call; {@code headers} and {@code body} are sent as given. */
    public record Request(HttpMethod method, URI uri, Map<String, String> headers, byte[] body,
            Duration readTimeout) {

        @Override
        public String toString() {
            return "Request[" + method + "]";
        }
    }

    /** A response of any status; {@code body} is cut at the character cap and {@code truncated} says so. */
    public record Response(int status, String contentType, String body, boolean truncated) {
    }

    private final HttpClient client;
    private final Duration retryPause;
    private final Map<Duration, RestClient> clients = new ConcurrentHashMap<>();

    public OutboundHttp() {
        this(selectorFromSystemProperties(), RETRY_PAUSE);
    }

    OutboundHttp(ProxySelector selector, Duration retryPause) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(selector)
                .build();
        this.retryPause = retryPause;
    }

    /**
     * The proxy of {@code https.proxyHost} and {@code https.proxyPort} for every request, ignoring the JVM's
     * bypass list, so the proxy's allowlist and private-range block apply to member-chosen hosts too; the
     * JVM default selector when no proxy is configured.
     */
    static ProxySelector selectorFromSystemProperties() {
        String host = System.getProperty("https.proxyHost");
        if (host == null || host.isBlank()) {
            return ProxySelector.getDefault();
        }
        String port = System.getProperty("https.proxyPort", "80");
        try {
            return ProxySelector.of(new InetSocketAddress(host, Integer.parseInt(port.trim())));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("https.proxyPort is not a number");
        }
    }

    /**
     * Sends the request, at most twice and only while nothing was written.
     *
     * @param connector the connector key, used in the log line only
     * @throws ConnectorException {@code UPSTREAM_UNAVAILABLE}, retryable when nothing was written
     */
    public Response execute(String connector, Request request) {
        RestClient http = clients.computeIfAbsent(request.readTimeout(), this::newClient);
        for (int attempt = 1;; attempt++) {
            try {
                Response response = send(http, request);
                LOG.info("Outbound {} {} status {} body {} chars", connector, request.method(), response.status(),
                        response.body().length());
                return response;
            } catch (RuntimeException ex) {
                OutboundFailurePhase phase = OutboundFailurePhase.classify(ex);
                LOG.warn("Outbound {} {} failed ({}) in {} on attempt {}", connector, request.method(),
                        ex.getClass().getSimpleName(), phase, attempt);
                if (!OutboundRetryPolicy.retry(phase, attempt)) {
                    throw unavailable(phase == OutboundFailurePhase.CONNECT_PHASE);
                }
                if (!pause()) {
                    throw unavailable(false);
                }
            }
        }
    }

    private RestClient newClient(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static Response send(RestClient http, Request request) {
        RestClient.RequestBodySpec spec = http.method(request.method())
                .uri(request.uri())
                .headers(headers -> request.headers().forEach(headers::set));
        if (request.body() != null) {
            spec = spec.body(request.body());
        }
        return spec.exchange((clientRequest, clientResponse) -> {
            String contentType = clientResponse.getHeaders().getFirst("Content-Type");
            return read(clientResponse.getStatusCode().value(), contentType, clientResponse.getBody());
        });
    }

    private static Response read(int status, String contentType, InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
        boolean truncated = bytes.length > MAX_BODY_BYTES;
        String text = new String(bytes, 0, Math.min(bytes.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
        if (text.length() > MAX_BODY_CHARS) {
            text = text.substring(0, MAX_BODY_CHARS);
            truncated = true;
        }
        return new Response(status, contentType, text, truncated);
    }

    private boolean pause() {
        try {
            Thread.sleep(retryPause);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ConnectorException unavailable(boolean retryable) {
        return new ConnectorException(Reason.UPSTREAM_UNAVAILABLE, retryable,
                new LocalizedMessage("connector.upstreamUnavailable"), null, null);
    }
}
