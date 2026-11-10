package com.flowcortex.connector.application.http;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The secret half of an HTTP connector: the token and the extra-header values, stored as one JSON
 * payload in the encrypted token slot so the plain metadata holds header names only.
 */
final class HttpSecret {

    /** The decoded payload; {@code toString} omits every value. */
    record Decoded(String token, Map<String, String> extraHeaders) {

        @Override
        public String toString() {
            return "Decoded[extraHeaders=" + extraHeaders.keySet() + "]";
        }
    }

    private record Payload(String token, Map<String, String> extraHeaders) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpSecret() {
    }

    static String encode(String token, Map<String, String> extraHeaders) {
        return JSON.writeValueAsString(new Payload(token, new LinkedHashMap<>(extraHeaders)));
    }

    /** @throws ConnectorException {@code CREDENTIAL_UNREADABLE} when the payload is not what {@link #encode} wrote */
    static Decoded decode(String payload) {
        try {
            Payload parsed = JSON.readValue(payload, Payload.class);
            if (parsed == null || parsed.token() == null || parsed.token().isEmpty()) {
                throw ConnectorException.of(Reason.CREDENTIAL_UNREADABLE);
            }
            Map<String, String> headers = parsed.extraHeaders() == null ? Map.of()
                    : new LinkedHashMap<>(parsed.extraHeaders());
            return new Decoded(parsed.token(), headers);
        } catch (ConnectorException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ConnectorException.of(Reason.CREDENTIAL_UNREADABLE);
        }
    }
}
