package com.flowcortex.chat.turn;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One counter tick and one log line per finished turn or refused send. Never the message text, the answer
 * or anything naming the member.
 */
@Component("chatTelemetry")
class ChatTelemetry {

    static final String TURN_COUNTER = "chat.turn.outcome";
    static final String REFUSED_COUNTER = "chat.send.refused";

    private static final Logger LOG = LoggerFactory.getLogger(ChatTelemetry.class);

    private final MeterRegistry meters;

    ChatTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void turn(String outcome, String detail, int inputTokens, int outputTokens, long startedNanos) {
        meters.counter(TURN_COUNTER, "outcome", outcome).increment();
        LOG.info("chat.turn outcome={} detail={} input_tokens={} output_tokens={} latency_ms={}", outcome, detail,
                inputTokens, outputTokens, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    void refused(String reason) {
        meters.counter(REFUSED_COUNTER, "reason", reason).increment();
        LOG.info("chat.send outcome=refused reason={}", reason);
    }
}
