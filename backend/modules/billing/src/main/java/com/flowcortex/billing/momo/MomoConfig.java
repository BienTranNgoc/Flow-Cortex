package com.flowcortex.billing.momo;

import com.flowcortex.billing.provider.PaymentOrderLookup;
import com.flowcortex.billing.provider.ProviderCircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the MoMo adapter only when {@code flowcortex.billing.momo.enabled} is true, after
 * {@link MomoStartupCheck} has passed; otherwise no MoMo bean exists and nothing leaves for MoMo.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MomoProperties.class)
class MomoConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    @ConditionalOnProperty(name = "flowcortex.billing.momo.enabled", havingValue = "true")
    MomoPaymentAdapter momoPaymentAdapter(MomoProperties properties, ObjectProvider<PaymentOrderLookup> lookup,
            MeterRegistry meters, Clock clock) {
        List<String> problems = MomoStartupCheck.problems(properties, lookup.getIfAvailable() != null);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("MoMo is enabled but its configuration is unusable:\n - "
                    + String.join("\n - ", problems));
        }
        MomoTelemetry telemetry = new MomoTelemetry(meters);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(
                new ProviderCircuitBreaker.CircuitPolicy(properties.circuit().enabled(),
                        properties.circuit().failureThreshold(), properties.circuit().openDuration()),
                clock, telemetry);
        MomoTransport transport = MomoTransport.http(properties.baseUrl(), CONNECT_TIMEOUT, REQUEST_TIMEOUT);
        return new MomoPaymentAdapter(properties, transport, breaker, telemetry, clock);
    }
}
