package com.flowcortex.billing.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Counts consecutive unusable answers (a {@link ProviderTransportException}) and, past the threshold, refuses
 * calls until the open period passes; then one probe call decides. Any other outcome of the supplier, including
 * a provider answer carrying an error code, is usable. One instance per provider.
 */
public final class ProviderCircuitBreaker {

    /** Receives every refusal and every state change; called while the breaker's lock is held, so keep it cheap. */
    public interface CircuitListener {

        CircuitListener NONE = new CircuitListener() {
            @Override
            public void refused(String provider, String endpoint, CircuitState state) {
            }

            @Override
            public void transitioned(String provider, CircuitState from, CircuitState to) {
            }
        };

        void refused(String provider, String endpoint, CircuitState state);

        void transitioned(String provider, CircuitState from, CircuitState to);
    }

    /** The breaker's configuration: {@code enabled=false} lets every call through. */
    public record CircuitPolicy(boolean enabled, int failureThreshold, Duration openDuration) {

        public CircuitPolicy {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("failureThreshold must be at least 1");
            }
            if (openDuration == null || openDuration.isZero() || openDuration.isNegative()) {
                throw new IllegalArgumentException("openDuration must be positive");
            }
        }
    }

    private final CircuitPolicy policy;
    private final Clock clock;
    private final CircuitListener listener;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    public ProviderCircuitBreaker(CircuitPolicy policy, Clock clock, CircuitListener listener) {
        this.policy = policy;
        this.clock = clock;
        this.listener = listener;
    }

    public <T> T run(String provider, String endpoint, Supplier<T> call) {
        if (!policy.enabled()) {
            return call.get();
        }
        boolean probe = admit(provider, endpoint);
        boolean settled = false;
        try {
            T result = call.get();
            settled = true;
            usable(provider, probe);
            return result;
        } catch (ProviderTransportException e) {
            settled = true;
            unusable(provider, probe);
            throw e;
        } finally {
            if (!settled && probe) {
                releaseProbe();
            }
        }
    }

    private synchronized boolean admit(String provider, String endpoint) {
        Instant now = clock.instant();
        switch (state) {
            case CLOSED:
                return false;
            case OPEN:
                Instant retryAt = openedAt.plus(policy.openDuration());
                if (now.isBefore(retryAt)) {
                    refuse(provider, endpoint, CircuitState.OPEN, retryAt);
                }
                transition(provider, CircuitState.HALF_OPEN);
                probeInFlight = true;
                return true;
            default:
                if (probeInFlight) {
                    refuse(provider, endpoint, CircuitState.HALF_OPEN, now);
                }
                probeInFlight = true;
                return true;
        }
    }

    private void refuse(String provider, String endpoint, CircuitState seen, Instant retryAt) {
        listener.refused(provider, endpoint, seen);
        throw new ProviderCircuitOpenException(provider, endpoint, seen, retryAt);
    }

    private synchronized void usable(String provider, boolean probe) {
        if (probe) {
            probeInFlight = false;
            consecutiveFailures = 0;
            transition(provider, CircuitState.CLOSED);
        } else if (state == CircuitState.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    private synchronized void unusable(String provider, boolean probe) {
        if (probe) {
            probeInFlight = false;
            open(provider);
        } else if (state == CircuitState.CLOSED && ++consecutiveFailures >= policy.failureThreshold()) {
            open(provider);
        }
    }

    private synchronized void releaseProbe() {
        probeInFlight = false;
    }

    private void open(String provider) {
        openedAt = clock.instant();
        transition(provider, CircuitState.OPEN);
    }

    private void transition(String provider, CircuitState to) {
        CircuitState from = state;
        if (from == to) {
            return;
        }
        state = to;
        listener.transitioned(provider, from, to);
    }
}
