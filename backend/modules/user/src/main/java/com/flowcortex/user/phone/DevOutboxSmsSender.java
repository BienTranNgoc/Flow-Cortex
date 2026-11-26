package com.flowcortex.user.phone;

import com.flowcortex.shared.web.LocalizedMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Development {@link SmsSender}, present only with {@code flowcortex.sms.sender=outbox}: nothing
 * reaches a carrier, every SMS lands in {@code sms_outbox}, worded from the message bundle in the
 * message's language.
 */
@Component
@ConditionalOnProperty(name = "flowcortex.sms.sender", havingValue = "outbox")
class DevOutboxSmsSender implements SmsSender {

    private static final Logger LOG = LoggerFactory.getLogger(DevOutboxSmsSender.class);

    private final SmsOutboxRepository outbox;
    private final Clock clock;
    private final MessageSource messages;

    DevOutboxSmsSender(SmsOutboxRepository outbox, Clock clock, MessageSource messages) {
        this.outbox = outbox;
        this.clock = clock;
        this.messages = messages;
    }

    @Override
    public SmsDelivery send(SmsMessage message) {
        String body = messages.getMessage(textOf(message), message.locale());
        outbox.save(new SmsOutboxEntry(UUID.randomUUID(), message.phoneE164(), body, Instant.now(clock)));
        LOG.info("SMS written to the development outbox: phone={}", PhoneNumbers.forLog(message.phoneE164()));
        return SmsDelivery.ACCEPTED;
    }

    private static LocalizedMessage textOf(SmsMessage message) {
        return switch (message.kind()) {
            case OTP -> new LocalizedMessage("sms.otp.body", message.code());
            case PHONE_CHANGED -> new LocalizedMessage("sms.phoneChanged.body");
            case PHONE_CHANGE_REVERTED -> new LocalizedMessage("sms.phoneChangeReverted.body");
        };
    }
}
