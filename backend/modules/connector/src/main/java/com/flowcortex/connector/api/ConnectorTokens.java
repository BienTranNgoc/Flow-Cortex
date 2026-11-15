package com.flowcortex.connector.api;

import java.time.Instant;
import java.util.List;

/** Plaintext tokens of one connection; {@code toString} omits them so a log line cannot leak one. */
public record ConnectorTokens(String accessToken, String refreshToken, Instant expiresAt, List<String> scopes) {

    @Override
    public String toString() {
        return "ConnectorTokens[expiresAt=" + expiresAt + ", scopes=" + scopes + "]";
    }
}
