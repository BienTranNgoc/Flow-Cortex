package com.flowcortex.connector.api;

import java.util.Map;

/**
 * One call of an HTTP connector. {@code method} defaults to {@code GET}; {@code body} is sent as-is when a
 * string and as JSON otherwise, for {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} only.
 * {@code toString} omits headers and body, which may carry data the caller does not want logged.
 */
public record HttpConnectorRequest(String key, String method, String url, Map<String, String> headers,
        Object body) {

    @Override
    public String toString() {
        return "HttpConnectorRequest[key=" + key + ", method=" + method + "]";
    }
}
