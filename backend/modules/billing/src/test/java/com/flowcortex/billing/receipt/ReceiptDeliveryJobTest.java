package com.flowcortex.billing.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.mail.api.Mailer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ReceiptDeliveryJobTest {

    private final ReceiptDeliveryRepository deliveries = mock(ReceiptDeliveryRepository.class);
    private final ReceiptMail receiptMail = mock(ReceiptMail.class);
    private final Mailer mailer = mock(Mailer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ReceiptDeliveryJob job(String publicUrl) {
        return new ReceiptDeliveryJob(deliveries, receiptMail, mailer, new ReceiptTelemetry(meters),
                Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC), publicUrl);
    }

    @Test
    void withoutThePublicUrlAPassTouchesNoRowSendsNothingCountsPausedAndWarnsOnce() {
        Logger log = (Logger) LoggerFactory.getLogger(ReceiptDeliveryJob.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        log.addAppender(lines);
        try {
            job(" ").run();
        } finally {
            log.detachAppender(lines);
        }

        verifyNoInteractions(deliveries, receiptMail, mailer);
        assertThat(meters.counter("billing.receipt.delivery", "outcome", "paused").count()).isEqualTo(1);
        assertThat(lines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains("outcome=paused");
        });
    }
}
