package com.flowcortex.billing.momo;

import com.flowcortex.billing.provider.CircuitState;
import com.flowcortex.billing.provider.ProviderCircuitBreaker;
import com.flowcortex.billing.provider.ProviderTransportException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts and times MoMo calls and the circuit's refusals and transitions. No tag or log line carries a
 * credential, a request value or an address; a result code outside MoMo's numeric vocabulary is tagged
 * {@code other}.
 */
final class MomoTelemetry implements ProviderCircuitBreaker.CircuitListener {

    /** The {@code side} tag for a request MoMo refused over its signature. */
    static final String OUTBOUND_REJECTED = "outbound_rejected";

    private static final Logger LOG = LoggerFactory.getLogger(MomoTelemetry.class);
    private static final String PROVIDER = "momo";
    private static final Pattern RESULT_CODE = Pattern.compile("^-?[0-9]{1,8}$");

    private final MeterRegistry meters;

    MomoTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /** A call MoMo answered, whatever the result code. */
    void answered(String endpoint, String resultCode, Duration elapsed) {
        Counter.builder("payment.provider.call")
                .description("Provider calls that got an answer, by result code")
                .tag("provider", PROVIDER)
                .tag("endpoint", endpoint)
                .tag("result_code", boundedCode(resultCode))
                .register(meters)
                .increment();
        timer(endpoint, elapsed);
    }

    /** A call that produced no usable answer. */
    void failed(String endpoint, ProviderTransportException.Kind kind, Duration elapsed) {
        Counter.builder("payment.provider.call.failure")
                .description("Provider calls with no usable answer, by failure kind")
                .tag("provider", PROVIDER)
                .tag("endpoint", endpoint)
                .tag("kind", kind.value())
                .register(meters)
                .increment();
        timer(endpoint, elapsed);
        LOG.warn("payment_provider_call_failed provider={} endpoint={} kind={}", PROVIDER, endpoint, kind.value());
    }

    void signatureFailure(String side) {
        Counter.builder("payment.provider.signature.failure")
                .description("Signatures a provider or we refused")
                .tag("provider", PROVIDER)
                .tag("side", side)
                .register(meters)
                .increment();
    }

    @Override
    public void refused(String provider, String endpoint, CircuitState state) {
        Counter.builder("payment.provider.circuit.refused")
                .description("Calls refused by an open provider circuit")
                .tag("provider", provider)
                .tag("endpoint", endpoint)
                .tag("state", state.value())
                .register(meters)
                .increment();
    }

    @Override
    public void transitioned(String provider, CircuitState from, CircuitState to) {
        Counter.builder("payment.provider.circuit.transition")
                .description("Provider circuit state changes")
                .tag("provider", provider)
                .tag("from", from.value())
                .tag("to", to.value())
                .register(meters)
                .increment();
        LOG.warn("payment_provider_circuit provider={} from={} to={}", provider, from.value(), to.value());
    }

    private void timer(String endpoint, Duration elapsed) {
        Timer.builder("payment.provider.call.duration")
                .description("Time spent in provider calls")
                .tag("provider", PROVIDER)
                .tag("endpoint", endpoint)
                .register(meters)
                .record(elapsed);
    }

    private static String boundedCode(String resultCode) {
        return resultCode != null && RESULT_CODE.matcher(resultCode).matches() ? resultCode : "other";
    }
}
