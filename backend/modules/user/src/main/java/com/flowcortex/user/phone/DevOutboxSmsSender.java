package com.flowcortex.user.phone;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The only {@link SmsSender}: nothing reaches a carrier, every SMS lands in {@code sms_outbox}. */
@Component
class DevOutboxSmsSender implements SmsSender {

    private static final Logger LOG = LoggerFactory.getLogger(DevOutboxSmsSender.class);

    private final SmsOutboxRepository outbox;
    private final Clock clock;

    DevOutboxSmsSender(SmsOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void send(String phoneE164, String body) {
        outbox.save(new SmsOutboxEntry(UUID.randomUUID(), phoneE164, body, Instant.now(clock)));
        LOG.info("SMS written to the development outbox: phone={}", PhoneNumbers.forLog(phoneE164));
    }
}
