package com.flowcortex.connector.application.http;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.HttpConnectorRequest;
import com.flowcortex.shared.web.LocalizedMessage;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.json.JsonMapper;

/** The pure rules of the HTTP connector: key, auth config, headers, credential injection, method, body cap. */
final class HttpConnectorRules {

    static final int MAX_EXTRA_HEADERS = 10;
    static final int MAX_HEADER_VALUE = 1024;
    static final int MAX_BODY_CHARS = 100_000;

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_-]{1,49}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESERVED_KEYS = Set.of("google", "slack", "telegram");
    private static final Set<String> AUTH_TYPES = Set.of("bearer", "header", "query");
    /** Headers that steer the connection or frame the message; no caller, extra or auth header may be one. */
    private static final Set<String> TRANSPORT_HEADERS = Set.of("proxy-authorization", "host", "content-length",
            "transfer-encoding", "connection", "upgrade", "expect", "te", "keep-alive", "proxy-connection");
    /** Headers that carry a credential: refused as extra or caller headers, allowed as the auth header. */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "cookie");
    private static final Map<String, HttpMethod> METHODS = Map.of("GET", HttpMethod.GET, "POST", HttpMethod.POST,
            "PUT", HttpMethod.PUT, "PATCH", HttpMethod.PATCH, "DELETE", HttpMethod.DELETE, "HEAD", HttpMethod.HEAD);
    private static final Set<HttpMethod> WITH_BODY = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
            HttpMethod.DELETE);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A saved connector's non-secret shape: normalized base URL and how the token is injected. */
    record Connection(String baseUrl, String authType, String headerName, String paramName) {
    }

    /** What a call sends: the final URI, the headers with the credential injected, and the body bytes. */
    record Prepared(HttpMethod method, URI uri, Map<String, String> headers, byte[] body) {
    }

    /** A response body cut at the cap. */
    record Capped(String text, boolean truncated) {
    }

    private HttpConnectorRules() {
    }

    /** The key trimmed and lower-cased, or empty when it breaks the pattern or is reserved. */
    static Optional<String> normalizeKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return KEY.matcher(key).matches() && !RESERVED_KEYS.contains(key) ? Optional.of(key) : Optional.empty();
    }

    /**
     * Validates what a member typed for the connector's base URL and auth and returns its stored shape.
     * {@code authType} must already be one of {@code bearer}, {@code header}, {@code query}.
     *
     * @throws ConnectorException {@code INVALID_INPUT} with the key of the first rule broken
     */
    static Connection connection(String baseUrl, String authType, String token, String headerName,
            String paramName) {
        String type = authType == null ? "" : authType.trim().toLowerCase(Locale.ROOT);
        if (!AUTH_TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported auth type");
        }
        if (token == null || token.isBlank() || unsendable(token.trim())) {
            throw tokenRequired();
        }
        String header = null;
        String param = null;
        if (type.equals("header")) {
            header = headerName == null ? "" : headerName.trim();
            if (header.isEmpty()) {
                throw headerNameRequired();
            }
            if (!HEADER_NAME.matcher(header).matches() || TRANSPORT_HEADERS.contains(lower(header))) {
                throw headerNotAllowed();
            }
        } else if (type.equals("query")) {
            param = paramName == null ? "" : paramName.trim();
            if (param.isEmpty()) {
                throw paramNameRequired();
            }
        }
        String base = BaseUrlPin.normalize(baseUrl).orElseThrow(HttpConnectorRules::baseUrlInvalid);
        return new Connection(base, type, header, param);
    }

    /** Validates the extra headers of a save and returns them with trimmed names, in order. */
    static Map<String, String> extraHeaders(Map<String, String> raw, Connection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null) {
            return headers;
        }
        if (raw.size() > MAX_EXTRA_HEADERS) {
            throw headerNotAllowed();
        }
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (!validHeaderName(name) || isAuthHeader(connection, name) || !seen.add(lower(name))
                    || value == null || value.length() > MAX_HEADER_VALUE || unsendable(value)) {
                throw headerNotAllowed();
            }
            headers.put(name, value);
        }
        return headers;
    }

    /** The plain metadata stored beside the encrypted token: names only, never a value. */
    static Map<String, Object> metadata(String label, Connection connection, Map<String, String> extraHeaders) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "http");
        metadata.put("label", label);
        metadata.put("authType", connection.authType());
        metadata.put("baseUrl", connection.baseUrl());
        metadata.put("headerName", connection.headerName());
        metadata.put("paramName", connection.paramName());
        metadata.put("extraHeaderNames", new ArrayList<>(extraHeaders.keySet()));
        return metadata;
    }

    /** The stored label, or the key when none was given. */
    static String label(String raw, String key) {
        return raw == null || raw.isBlank() ? key : raw.trim();
    }

    /** The method of a call: {@code GET} by default, else one of the six supported. */
    static HttpMethod method(String raw) {
        if (raw == null || raw.isBlank()) {
            return HttpMethod.GET;
        }
        HttpMethod method = METHODS.get(raw.trim().toUpperCase(Locale.ROOT));
        if (method == null) {
            throw new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.http.methodInvalid"));
        }
        return method;
    }

    /**
     * Builds the request of a call. The URL is pinned before the credential is injected, so a refused URL
     * is never decorated, and nothing is sent when any rule fails.
     *
     * @throws ConnectorException {@code INVALID_INPUT} (method, headers) or {@code OUT_OF_BASE_URL}
     */
    static Prepared prepare(Connection connection, HttpSecret.Decoded secret, HttpConnectorRequest request) {
        HttpMethod method = method(request.method());
        String url = request.url() == null ? null : request.url().trim();
        if (!BaseUrlPin.allows(connection.baseUrl(), url)) {
            throw new ConnectorException(Reason.OUT_OF_BASE_URL,
                    new LocalizedMessage("connector.http.urlOutsideBaseUrl"));
        }
        Map<String, String> headers = new LinkedHashMap<>(secret.extraHeaders());
        Set<String> taken = new HashSet<>();
        secret.extraHeaders().keySet().forEach(name -> taken.add(lower(name)));
        boolean contentType = taken.contains("content-type");
        if (request.headers() != null) {
            for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = entry.getValue();
                if (!validHeaderName(name) || isAuthHeader(connection, name) || taken.contains(lower(name))
                        || value == null || unsendable(value)) {
                    throw headerNotAllowed();
                }
                taken.add(lower(name));
                contentType |= lower(name).equals("content-type");
                headers.put(name, value);
            }
        }
        String target = url;
        switch (connection.authType()) {
            case "bearer" -> headers.put("Authorization", "Bearer " + secret.token());
            case "header" -> headers.put(connection.headerName(), secret.token());
            default -> target = withQueryToken(url, connection.paramName(), secret.token());
        }
        byte[] body = WITH_BODY.contains(method) ? encodeBody(request.body()) : null;
        if (body != null && !contentType) {
            headers.put("Content-Type", "application/json");
        }
        return new Prepared(method, URI.create(target), headers, body);
    }

    /** Cuts a body at {@value #MAX_BODY_CHARS} characters; {@code alreadyTruncated} carries an earlier cut. */
    static Capped cap(String body, boolean alreadyTruncated) {
        String text = body == null ? "" : body;
        if (text.length() > MAX_BODY_CHARS) {
            return new Capped(text.substring(0, MAX_BODY_CHARS), true);
        }
        return new Capped(text, alreadyTruncated);
    }

    private static String withQueryToken(String url, String param, String token) {
        int hash = url.indexOf('#');
        String bare = hash < 0 ? url : url.substring(0, hash);
        int question = bare.indexOf('?');
        if (question >= 0 && carriesParam(bare.substring(question + 1), param)) {
            throw headerNotAllowed();
        }
        String separator = question < 0 ? "?" : bare.endsWith("?") || bare.endsWith("&") ? "" : "&";
        return bare + separator + encode(param) + "=" + encode(token);
    }

    private static boolean carriesParam(String query, String param) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equals(param) || decode(name).equals(param)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] encodeBody(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        return JSON.writeValueAsBytes(body);
    }

    private static boolean validHeaderName(String name) {
        return HEADER_NAME.matcher(name).matches() && !TRANSPORT_HEADERS.contains(lower(name))
                && !CREDENTIAL_HEADERS.contains(lower(name));
    }

    private static boolean isAuthHeader(Connection connection, String name) {
        return connection.headerName() != null && connection.headerName().equalsIgnoreCase(name);
    }

    /** A control character (other than tab) or one outside ISO-8859-1: the client cannot send it in a header. */
    private static boolean unsendable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return text;
        }
    }

    static List<String> names(Object stored) {
        if (!(stored instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    static ConnectorException keyInvalid() {
        return new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.http.keyInvalid"));
    }

    static ConnectorException baseUrlInvalid() {
        return new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.http.baseUrlInvalid"));
    }

    static ConnectorException tokenRequired() {
        return new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.http.tokenRequired"));
    }

    static ConnectorException headerNameRequired() {
        return new ConnectorException(Reason.INVALID_INPUT,
                new LocalizedMessage("connector.http.headerNameRequired"));
    }

    static ConnectorException paramNameRequired() {
        return new ConnectorException(Reason.INVALID_INPUT,
                new LocalizedMessage("connector.http.paramNameRequired"));
    }

    static ConnectorException headerNotAllowed() {
        return new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.http.headerNotAllowed"));
    }
}
