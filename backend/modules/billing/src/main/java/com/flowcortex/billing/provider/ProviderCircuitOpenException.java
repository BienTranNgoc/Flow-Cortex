package com.flowcortex.billing.provider;

import java.time.Instant;

/** A call refused by an open circuit. Nothing was sent, so the operation is not in doubt and may be retried. */
public class ProviderCircuitOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String provider;
    private final String endpoint;
    private final CircuitState state;
    private final Instant retryAt;

    public ProviderCircuitOpenException(String provider, String endpoint, CircuitState state, Instant retryAt) {
        super("provider circuit " + state.value() + ": provider=" + provider + " endpoint=" + endpoint
                + " retryAt=" + retryAt + "; nothing was sent");
        this.provider = provider;
        this.endpoint = endpoint;
        this.state = state;
        this.retryAt = retryAt;
    }

    public String provider() {
        return provider;
    }

    public String endpoint() {
        return endpoint;
    }

    public CircuitState state() {
        return state;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
