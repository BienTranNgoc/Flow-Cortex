package com.flowcortex.billing.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.billing.catalog.TierOrder.OrderableTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

class CatalogueCheckTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CatalogueTelemetry telemetry = new CatalogueTelemetry(meters);
    private final Logger checkLogger = (Logger) LoggerFactory.getLogger(CatalogueCheck.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logs.start();
        checkLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLog() {
        checkLogger.detachAppender(logs);
    }

    private static OrderableTier tier(String id, int position, long price) {
        return new OrderableTier(id, position, OptionalLong.of(price));
    }

    private CatalogueCheck check(boolean enabled, Supplier<List<OrderableTier>> tiers) {
        return new CatalogueCheck(tiers, telemetry, new CatalogueCheckProperties(enabled, Duration.ofHours(24)));
    }

    private static List<OrderableTier> cleanLadder() {
        return List.of(tier("free", 0, 0), tier("basic", 1, 849_000), tier("advanced", 2, 2_499_000));
    }

    private long checks(String trigger, String outcome) {
        var timer = meters.find("billing.catalogue.check").tag("trigger", trigger).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double violations(String kind) {
        var counter = meters.find("billing.catalogue.violation").tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<ILoggingEvent> errors() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    void aCleanCatalogueIsRecordedCleanWithNoViolationAndNoErrorLine() {
        assertThat(check(true, CatalogueCheckTest::cleanLadder).run("startup")).isEqualTo("clean");

        assertThat(checks("startup", "clean")).isEqualTo(1);
        assertThat(meters.find("billing.catalogue.violation").counters()).isEmpty();
        assertThat(errors()).isEmpty();
    }

    @Test
    void aDuplicateAndAnInversionAreEachCountedByKindAndLoggedOnce() {
        List<OrderableTier> tiers = List.of(tier("free", 0, 0), tier("basic", 1, 849_000),
                tier("advanced", 1, 2_499_000), tier("professional", 2, 800_000));

        assertThat(check(true, () -> tiers).run("scheduled")).isEqualTo("violations");

        assertThat(checks("scheduled", "violations")).isEqualTo(1);
        assertThat(violations("duplicate_position")).isEqualTo(1);
        assertThat(violations("price_inversion")).isEqualTo(1);
        assertThat(violations("unpriced")).isZero();
        assertThat(errors()).hasSize(2);
        assertThat(errors().get(1).getFormattedMessage()).contains("basic", "professional", "849000", "800000");
    }

    @Test
    void anUnreadableCatalogueIsRecordedUnreadableWithOneErrorLineAndNoEscape() {
        Supplier<List<OrderableTier>> down = () -> {
            throw new DataAccessResourceFailureException("db down");
        };

        assertThat(check(true, down).run("scheduled")).isEqualTo("unreadable");

        assertThat(checks("scheduled", "unreadable")).isEqualTo(1);
        assertThat(meters.find("billing.catalogue.violation").counters()).isEmpty();
        assertThat(errors()).hasSize(1);
        assertThat(errors().get(0).getFormattedMessage()).contains("DataAccessResourceFailureException");
    }

    @Test
    void aSwitchedOffCheckDoesNothingAndRecordsNothing() {
        boolean[] read = {false};
        CatalogueCheck off = check(false, () -> {
            read[0] = true;
            return cleanLadder();
        });

        off.onReady();
        off.run();

        assertThat(read[0]).isFalse();
        assertThat(meters.find("billing.catalogue.check").timers()).isEmpty();
    }

    @Test
    void theBootReportAndTheClusterJobPassTagTheirTrigger() {
        CatalogueCheck check = check(true, CatalogueCheckTest::cleanLadder);

        check.onReady();
        check.run();

        assertThat(checks("startup", "clean")).isEqualTo(1);
        assertThat(checks("scheduled", "clean")).isEqualTo(1);
    }

    @Test
    void theClusterJobIsNamedAndPacedByTheConfiguredInterval() {
        CatalogueCheck check = new CatalogueCheck(CatalogueCheckTest::cleanLadder, telemetry,
                new CatalogueCheckProperties(true, Duration.ofHours(6)));

        assertThat(check.name()).isEqualTo("billing.catalogue-check");
        assertThat(check.interval()).isEqualTo(Duration.ofHours(6));
        assertThat(new CatalogueCheckProperties(null, null))
                .isEqualTo(new CatalogueCheckProperties(true, Duration.ofHours(24)));
    }
}
