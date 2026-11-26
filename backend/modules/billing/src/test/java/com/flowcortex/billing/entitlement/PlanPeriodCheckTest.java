package com.flowcortex.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.billing.entitlement.PlanPeriodCheck.SoldCycle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PlanPeriodCheckTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EntitlementTelemetry telemetry = new EntitlementTelemetry(meters);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(EntitlementTelemetry.class);

    @BeforeEach
    void captureLog() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void theSeededMonthlyCatalogueHasNoShortfall() {
        PlanPeriodCheck check = new PlanPeriodCheck(() -> List.of(new SoldCycle("basic", "monthly"),
                new SoldCycle("advanced", "monthly"), new SoldCycle("professional", "monthly")), telemetry);

        check.run();

        assertThat(meters.find("billing.plan_period.shortfall").counters()).isEmpty();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void anUnreadableCatalogueIsLoggedOnceAndNothingEscapes() {
        PlanPeriodCheck check = new PlanPeriodCheck(() -> {
            throw new IllegalStateException("catalogue down");
        }, telemetry);

        assertThatCode(check::run).doesNotThrowAnyException();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("unreadable").contains("IllegalStateException");
        });
    }
}
