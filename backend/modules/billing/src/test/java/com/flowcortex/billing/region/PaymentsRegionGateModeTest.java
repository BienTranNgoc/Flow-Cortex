package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PaymentsRegionGateModeTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentsRegionGateMode.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    @Test
    void knownValuesParseToTheirMode() {
        assertThat(PaymentsRegionGateMode.parse("enforce")).isEqualTo(PaymentsRegionGateMode.ENFORCE);
        assertThat(PaymentsRegionGateMode.parse("off")).isEqualTo(PaymentsRegionGateMode.OFF);
        assertThat(PaymentsRegionGateMode.parse("shadow")).isEqualTo(PaymentsRegionGateMode.SHADOW);
        assertThat(logged.list).isEmpty();
    }

    @Test
    void unrecognisedOrAbsentValuesReadAsShadowAndTheNonEmptyOnesWarn() {
        assertThat(PaymentsRegionGateMode.parse("OFF")).isEqualTo(PaymentsRegionGateMode.SHADOW);
        assertThat(PaymentsRegionGateMode.parse("enforc")).isEqualTo(PaymentsRegionGateMode.SHADOW);
        assertThat(PaymentsRegionGateMode.parse("")).isEqualTo(PaymentsRegionGateMode.SHADOW);
        assertThat(PaymentsRegionGateMode.parse(null)).isEqualTo(PaymentsRegionGateMode.SHADOW);

        assertThat(logged.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(logged.list.get(0).getFormattedMessage()).contains("OFF");
        assertThat(logged.list.get(1).getFormattedMessage()).contains("enforc");
    }

    @Test
    void onlyEnforceWithAForeignVerdictRefuses() {
        for (PaymentsRegionGateMode mode : PaymentsRegionGateMode.values()) {
            for (RegionVerdict verdict : RegionVerdict.values()) {
                boolean refused = mode == PaymentsRegionGateMode.ENFORCE && verdict == RegionVerdict.FOREIGN;
                assertThat(mode.allows(verdict)).as("%s with %s", mode, verdict).isEqualTo(!refused);
            }
        }
    }
}
