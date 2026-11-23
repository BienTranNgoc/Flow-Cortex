package com.flowcortex.user.oauth;

/** The outcome of a code exchange: an OAuth refusal is a value, so the exchange's transaction commits it. */
sealed interface ExchangeResult {

    /** {@code expiresIn} in seconds. */
    record Granted(String accessToken, long expiresIn, String scope) implements ExchangeResult {
    }

    /** {@code error} is the RFC 6749 section 5.2 code. */
    record Denied(String error) implements ExchangeResult {
    }
}
