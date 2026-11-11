package com.flowcortex.billing.momo;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.momo.*}. MoMo is off unless {@code enabled}; with it on, {@link MomoStartupCheck}
 * refuses to start on an unusable set. {@code toString} omits every credential.
 *
 * @param ipnUrl the URL MoMo posts payment callbacks to; its path must be {@link MomoCallbackRoute#PATH}
 * @param offeredInstruments wire values of the instruments a payer may use, in order of preference
 * @param callbackSourceAllowlist comma-separated addresses or CIDR blocks MoMo calls back from
 */
@ConfigurationProperties("flowcortex.billing.momo")
public record MomoProperties(
        boolean enabled,
        String baseUrl,
        String partnerCode,
        String accessKey,
        String secretKey,
        String redirectUrl,
        String ipnUrl,
        List<String> offeredInstruments,
        String callbackSourceAllowlist,
        Circuit circuit) {

    /** The circuit breaker's settings; {@code enabled=false} sends every call. */
    public record Circuit(Boolean enabled, Integer failureThreshold, Duration openDuration) {

        public Circuit {
            enabled = enabled == null || enabled;
            failureThreshold = failureThreshold == null ? 5 : failureThreshold;
            openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        }
    }

    public MomoProperties {
        offeredInstruments = offeredInstruments == null ? List.of("wallet") : List.copyOf(offeredInstruments);
        circuit = circuit == null ? new Circuit(null, null, null) : circuit;
    }

    @Override
    public String toString() {
        return "MomoProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", offeredInstruments="
                + offeredInstruments + ", circuit=" + circuit + "]";
    }
}
