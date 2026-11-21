package com.flowcortex.billing.region;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Counts each assessed verdict and logs the non-Vietnamese ones; neither carries the address or a country. */
@Component
class RegionTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(RegionTelemetry.class);

    private final MeterRegistry meters;

    RegionTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void record(RegionVerdict verdict, PaymentsRegionGateMode mode, boolean viSignal, boolean refused) {
        String verdictTag = verdict.name().toLowerCase(Locale.ROOT);
        Counter.builder("payments.region.verdict")
                .description("Callers of payment-opening handlers placed by the region gate")
                .tag("verdict", verdictTag)
                .tag("mode", mode.value())
                .tag("vi_signal", Boolean.toString(viSignal))
                .tag("refused", Boolean.toString(refused))
                .register(meters)
                .increment();
        if (verdict != RegionVerdict.VN) {
            LOG.info("payments_region verdict={} mode={} vi_signal={} refused={}", verdictTag, mode.value(), viSignal,
                    refused);
        }
    }
}
