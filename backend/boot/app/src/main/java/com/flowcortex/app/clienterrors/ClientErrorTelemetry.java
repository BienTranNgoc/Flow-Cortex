package com.flowcortex.app.clienterrors;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One WARN line and one counter increment per accepted report. Carries nothing of the caller beyond
 * the redacted report, and never throws, so a broken appender or meter registry cannot change the answer.
 */
@Component
class ClientErrorTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(ClientErrorTelemetry.class);

    private final MeterRegistry meters;

    ClientErrorTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void accepted(ClientErrorReport report) {
        try {
            meters.counter("client_errors", "kind", report.kind().wire(), "app", report.app().wire()).increment();
        } catch (RuntimeException e) {
            LOG.error("client_error_telemetry_failed step=counter", e);
        }
        try {
            String lang = report.lang() == null ? "" : printable(report.lang());
            LOG.warn("client_error kind={} app={} name={} route={} lang={}",
                    report.kind().wire(), report.app().wire(), printable(report.name()), printable(report.route()), lang);
        } catch (RuntimeException e) {
            LOG.error("client_error_telemetry_failed step=log", e);
        }
    }

    /** Control characters would let a report forge log lines. */
    private static String printable(String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }
}
