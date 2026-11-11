package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The config of a {@code connector.call} step: which saved HTTP connector to call and the request to
 * send. {@link #parse} is the one reader, used when a workflow is saved and again when the step runs.
 * A fault is a short English sentence that names the offending field and never echoes a header value.
 */
record ConnectorCallConfig(String connector, String method, URI url, Map<String, String> headers, JsonNode body,
        boolean bodyFromInput) {

    /** Either the parsed config or the fault that refused it; exactly one is non-null. */
    record Result(ConnectorCallConfig config, String fault) {
    }

    private static final ObjectMapper READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> KEYS = Set.of("connector", "method", "url", "headers", "body", "bodyFromInput");
    private static final Pattern CONNECTOR_KEY = Pattern.compile("^[a-z][a-z0-9_-]{1,49}$");
    private static final Set<String> RESERVED_KEYS = Set.of("google", "slack", "telegram");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern HEADER_TOKEN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");
    private static final Set<String> REFUSED_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "expect", "te", "keep-alive",
            "proxy-connection");
    private static final Pattern CREDENTIAL_NAME =
            Pattern.compile("(?i)(auth|token|secret|api[-_]?key|session|password)");
    private static final int MAX_HEADERS = 10;
    private static final int MAX_HEADER_VALUE = 1024;
    private static final int MAX_URL = 2048;
    private static final int MAX_NAMED_LENGTH = 64;

    static Result parse(String configJson) {
        String fault = refusal(configJson);
        if (fault != null) {
            return new Result(null, fault);
        }
        return new Result(read(configJson), null);
    }

    /** What is wrong with {@code configJson}, or {@code null} when it is a valid config. */
    static String fault(String configJson) {
        return refusal(configJson);
    }

    private static String refusal(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return "config is required";
        }
        JsonNode root;
        try {
            root = READER.readTree(configJson);
        } catch (JsonProcessingException e) {
            return "config is not valid JSON";
        }
        if (root == null || !root.isObject()) {
            return "config must be a JSON object";
        }
        List<String> unknown = new ArrayList<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!KEYS.contains(name)) {
                unknown.add(shorten(name));
            }
        });
        if (!unknown.isEmpty()) {
            return "config has unknown key '" + unknown.get(0) + "'; allowed: connector, method, url, headers, "
                    + "body, bodyFromInput";
        }
        String fault = connectorFault(root.get("connector"));
        if (fault == null) {
            fault = urlFault(root.get("url"));
        }
        String method = methodOf(root);
        if (fault == null && method == null) {
            fault = "method must be one of GET, POST, PUT, PATCH, DELETE, HEAD";
        }
        if (fault == null) {
            fault = headersFault(root.get("headers"));
        }
        if (fault == null) {
            fault = bodyFault(root, method);
        }
        return fault;
    }

    private static ConnectorCallConfig read(String configJson) {
        try {
            JsonNode root = READER.readTree(configJson);
            Map<String, String> headers = new LinkedHashMap<>();
            JsonNode headerNodes = root.get("headers");
            if (headerNodes != null && headerNodes.isObject()) {
                headerNodes.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
            }
            JsonNode body = root.get("body");
            boolean hasBody = body != null && !body.isNull();
            return new ConnectorCallConfig(root.get("connector").asText(), methodOf(root),
                    new URI(root.get("url").asText()), headers, hasBody ? body : null,
                    root.path("bodyFromInput").asBoolean(false));
        } catch (JsonProcessingException | URISyntaxException e) {
            throw new IllegalStateException("config passed validation but could not be read", e);
        }
    }

    /** The method, defaulting to GET when absent; {@code null} when present but not one of the allowed. */
    private static String methodOf(JsonNode root) {
        JsonNode method = root.get("method");
        if (method == null) {
            return "GET";
        }
        return method.isTextual() && METHODS.contains(method.asText()) ? method.asText() : null;
    }

    private static String connectorFault(JsonNode connector) {
        if (connector == null || !connector.isTextual()) {
            return "connector is required and must be a text key";
        }
        String key = connector.asText();
        if (!CONNECTOR_KEY.matcher(key).matches()) {
            return "connector must be 2 to 50 characters: a lower-case letter, then lower-case letters, digits, "
                    + "'-' or '_'";
        }
        if (RESERVED_KEYS.contains(key)) {
            return "connector '" + key + "' is reserved for a built-in connector";
        }
        return null;
    }

    private static String urlFault(JsonNode url) {
        if (url == null || !url.isTextual()) {
            return "url is required and must be text";
        }
        String text = url.asText();
        if (text.length() > MAX_URL) {
            return "url is longer than " + MAX_URL + " characters";
        }
        URI parsed;
        try {
            parsed = new URI(text);
        } catch (URISyntaxException e) {
            return "url is not a valid URI";
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || parsed.getHost().isEmpty()) {
            return "url must be an absolute https URL with a host";
        }
        if (parsed.getRawUserInfo() != null) {
            return "url must not carry a user name or password; save the credential on the connector instead";
        }
        String rawQuery = parsed.getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String name = decode(pair.split("=", 2)[0]);
                if (CREDENTIAL_NAME.matcher(name).find()) {
                    return "url query parameter '" + shorten(name) + "' looks like a credential; "
                            + "save it on the connector instead of in the step";
                }
            }
        }
        return null;
    }

    private static String headersFault(JsonNode headers) {
        if (headers == null) {
            return null;
        }
        if (!headers.isObject()) {
            return "headers must be an object of text values";
        }
        if (headers.size() > MAX_HEADERS) {
            return "headers holds more than " + MAX_HEADERS + " entries";
        }
        for (Map.Entry<String, JsonNode> entry : (Iterable<Map.Entry<String, JsonNode>>) headers::fields) {
            String name = entry.getKey();
            if (!HEADER_TOKEN.matcher(name).matches()) {
                return "a header name is not a valid HTTP token";
            }
            String shown = shorten(name);
            if (REFUSED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return "header '" + shown + "' is set by the platform and cannot be given";
            }
            if (CREDENTIAL_NAME.matcher(name).find()) {
                return "header '" + shown + "' looks like a credential; save it on the connector instead of "
                        + "in the step";
            }
            JsonNode value = entry.getValue();
            if (!value.isTextual()) {
                return "header '" + shown + "' must have a text value";
            }
            String text = value.asText();
            if (text.length() > MAX_HEADER_VALUE) {
                return "header '" + shown + "' value is longer than " + MAX_HEADER_VALUE + " characters";
            }
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
                return "header '" + shown + "' value must not contain a line break";
            }
        }
        return null;
    }

    private static String bodyFault(JsonNode root, String method) {
        JsonNode body = root.get("body");
        boolean hasBody = body != null && !body.isNull();
        JsonNode fromInput = root.get("bodyFromInput");
        if (fromInput != null && !fromInput.isBoolean()) {
            return "bodyFromInput must be true or false";
        }
        boolean takesInput = fromInput != null && fromInput.asBoolean();
        if (hasBody && takesInput) {
            return "give either body or bodyFromInput, not both";
        }
        if ((hasBody || takesInput) && !BODY_METHODS.contains(method)) {
            return "a " + method + " request cannot carry a body";
        }
        return null;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private static String shorten(String name) {
        return name.length() <= MAX_NAMED_LENGTH ? name : name.substring(0, MAX_NAMED_LENGTH) + "...";
    }
}
