package com.flowcortex.billing.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

class CatalogueTelemetryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CatalogueTelemetry telemetry = new CatalogueTelemetry(meters);
    private final Logger telemetryLogger = (Logger) LoggerFactory.getLogger(CatalogueTelemetry.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logs.start();
        telemetryLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLog() {
        telemetryLogger.detachAppender(logs);
    }

    private long reads(String catalogue, String outcome) {
        var timer = meters.find("billing.catalogue.read").tag("catalogue", catalogue).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void aSuccessfulReadReturnsItsValueAndCountsASuccess() {
        String value = telemetry.observedRead("plans", () -> "rows");

        assertThat(value).isEqualTo("rows");
        assertThat(reads("plans", "success")).isEqualTo(1);
        assertThat(reads("plans", "failure")).isZero();
    }

    @Test
    void aFailedReadCountsAFailureAndRethrowsTheSameException() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");

        assertThatThrownBy(() -> telemetry.observedRead("topup_packages", () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(reads("topup_packages", "failure")).isEqualTo(1);
        assertThat(reads("topup_packages", "success")).isZero();
        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains("billing_catalogue_read")
                .contains("catalogue=topup_packages").contains("DataAccessResourceFailureException");
    }

    @Test
    void aCheckPassIsTimedByTriggerAndOutcome() {
        telemetry.recordCheck("scheduled", "violations", Duration.ofMillis(12));

        var timer = meters.find("billing.catalogue.check").tag("trigger", "scheduled").tag("outcome", "violations")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void eachViolationIsCountedByKind() {
        telemetry.recordViolation("duplicate_position");
        telemetry.recordViolation("price_inversion");
        telemetry.recordViolation("price_inversion");

        assertThat(meters.get("billing.catalogue.violation").tag("kind", "duplicate_position").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("billing.catalogue.violation").tag("kind", "price_inversion").counter().count())
                .isEqualTo(2);
    }
}
