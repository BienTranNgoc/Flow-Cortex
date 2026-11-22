package com.flowcortex.user.phone;

import io.micrometer.core.instrument.MeterRegistry;

/** Counters for the SMS carrier. Tags come from closed sets and never hold a number, token or member id. */
final class SmsCarrierTelemetry {

    static final String SEND = "sms.send";
    static final String RENEWAL = "sms.carrier.renewal";
    static final String LOW_BALANCE = "sms.carrier.low_balance";

    private final MeterRegistry meters;

    SmsCarrierTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /** {@code result}: accepted, duplicate, unconfirmed, number_rejected or unavailable. */
    void send(String sender, String result) {
        meters.counter(SEND, "sender", sender, "result", result).increment();
    }

    /** {@code outcome}: renewed, rejected or failed. */
    void renewal(String outcome) {
        meters.counter(RENEWAL, "outcome", outcome).increment();
    }

    void lowBalance() {
        meters.counter(LOW_BALANCE).increment();
    }
}
