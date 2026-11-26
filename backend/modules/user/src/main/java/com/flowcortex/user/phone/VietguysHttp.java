package com.flowcortex.user.phone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One {@code POST} to VietGuys, reported by phase. A failure up to and including {@code connect()}
 * (proxy, tunnel, DNS, TLS) is {@link NotSent}: no byte of the body left. A failure after it is
 * {@link Unconfirmed}: the body may have arrived. The phase decides, never the exception type,
 * because behind the egress proxy a refused or unreachable host surfaces as a plain
 * {@code IOException}.
 */
final class VietguysHttp {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** What came of one call. */
    sealed interface Outcome permits Answer, NotSent, Unconfirmed {
    }

    /** The carrier answered; the body is capped at 64 KiB and is the error stream from status 400. */
    record Answer(int status, byte[] body) implements Outcome {
    }

    /** Nothing was sent. {@code reason} is an exception class name, never a host, number or text. */
    record NotSent(String reason) implements Outcome {
    }

    /** The body may have been delivered and no answer proves it. */
    record Unconfirmed(String reason) implements Outcome {
    }

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final ProxySelector proxies;

    /** Production: the JVM default {@link ProxySelector}, so {@code https.proxyHost} still applies. */
    VietguysHttp(Duration connectTimeout, Duration readTimeout) {
        this(connectTimeout, readTimeout, null);
    }

    VietguysHttp(Duration connectTimeout, Duration readTimeout, ProxySelector proxies) {
        this.connectTimeoutMillis = millis(connectTimeout, "connectTimeout");
        this.readTimeoutMillis = millis(readTimeout, "readTimeout");
        this.proxies = proxies;
    }

    Outcome post(URI url, Map<String, String> headers, String contentType, byte[] body) {
        boolean connected = false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.toURL().openConnection(proxyFor(url));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", contentType);
            headers.forEach(connection::setRequestProperty);
            connection.connect();
            connected = true;
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Answer(status, readCapped(stream));
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getClass().getName();
            return connected ? new Unconfirmed(reason) : new NotSent(reason);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Proxy proxyFor(URI url) {
        ProxySelector selector = proxies != null ? proxies : ProxySelector.getDefault();
        List<Proxy> candidates = selector == null ? List.of() : selector.select(url);
        return candidates == null || candidates.isEmpty() ? Proxy.NO_PROXY : candidates.get(0);
    }

    private static byte[] readCapped(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        try (stream) {
            return stream.readNBytes(MAX_BODY_BYTES);
        }
    }

    private static int millis(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE);
    }
}
