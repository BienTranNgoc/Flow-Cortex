package com.flowcortex.billing.momo;

import com.flowcortex.billing.provider.ProviderTransportException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Posts a signed JSON request to MoMo and returns the JSON answer. Anything else (no answer, an error page,
 * JSON that carries neither {@code resultCode} nor {@code errorCode}) is an unresolved
 * {@link ProviderTransportException}: the request may have taken effect.
 */
interface MomoTransport {

    String PROVIDER = "momo";

    /** Largest answer read; MoMo's are a few hundred bytes. */
    int MAX_ANSWER_BYTES = 1 << 20;

    /** The answer to {@code body} posted to {@code path} on MoMo's host. */
    JsonNode post(String path, ObjectNode body);

    /**
     * The JDK client with its default proxy selector, so the JVM's proxy properties (the egress proxy) apply,
     * and redirects never followed.
     */
    static MomoTransport http(String baseUrl, Duration connectTimeout, Duration requestTimeout) {
        return new HttpMomoTransport(baseUrl, connectTimeout, requestTimeout);
    }

    final class HttpMomoTransport implements MomoTransport {

        private static final JsonMapper MAPPER = JsonMapper.builder().build();

        private final String baseUrl;
        private final Duration requestTimeout;
        private final HttpClient client;

        private HttpMomoTransport(String baseUrl, Duration connectTimeout, Duration requestTimeout) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.requestTimeout = requestTimeout;
            this.client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(connectTimeout)
                    .build();
        }

        @Override
        public JsonNode post(String path, ObjectNode body) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            int status;
            byte[] answer;
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                status = response.statusCode();
                try (InputStream stream = response.body()) {
                    answer = stream.readNBytes(MAX_ANSWER_BYTES + 1);
                }
            } catch (HttpTimeoutException e) {
                throw new ProviderTransportException(ProviderTransportException.Kind.TIMEOUT, PROVIDER, path, null, e);
            } catch (IOException e) {
                throw new ProviderTransportException(ProviderTransportException.Kind.NETWORK, PROVIDER, path, null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderTransportException(ProviderTransportException.Kind.NETWORK, PROVIDER, path, null, e);
            }
            return classify(path, status, answer);
        }

        private static JsonNode classify(String path, int status, byte[] answer) {
            boolean success = status >= 200 && status < 300;
            ProviderTransportException.Kind unusable = success
                    ? ProviderTransportException.Kind.MALFORMED_RESPONSE
                    : ProviderTransportException.Kind.HTTP_STATUS;
            if (answer.length > MAX_ANSWER_BYTES) {
                throw new ProviderTransportException(unusable, PROVIDER, path, status);
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(answer);
            } catch (JacksonException e) {
                throw new ProviderTransportException(unusable, PROVIDER, path, status);
            }
            if (!json.isObject() || !(json.has("resultCode") || json.has("errorCode"))) {
                throw new ProviderTransportException(unusable, PROVIDER, path, status);
            }
            return json;
        }
    }
}
