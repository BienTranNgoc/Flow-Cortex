package com.flowcortex.user.phone;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the phone flow. Tags come from closed outcome sets and never hold a number, hash,
 * address or member id.
 */
@Component
class PhoneTelemetry {

    static final String SEND = "phone_otp.send";
    static final String VERIFY = "phone_otp.verify";
    static final String GATE_BLOCKED = "phone_gate.blocked";
    static final String CHANGE = "phone_change";

    private final MeterRegistry meters;

    PhoneTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void send(String outcome) {
        meters.counter(SEND, "outcome", outcome).increment();
    }

    void verify(String outcome) {
        meters.counter(VERIFY, "outcome", outcome).increment();
    }

    void gateBlocked() {
        meters.counter(GATE_BLOCKED).increment();
    }

    void change(String step, String outcome) {
        meters.counter(CHANGE, "step", step, "outcome", outcome).increment();
    }
}
