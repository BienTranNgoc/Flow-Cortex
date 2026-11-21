package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.billing.settings.PaymentSettingGroup;
import com.flowcortex.billing.settings.PaymentSettingMeta;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PaymentsRegionModeSourceTest {

    private final Settings settings = mock(Settings.class);
    private final PaymentsRegionModeSource source = new PaymentsRegionModeSource(settings);
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

    private void effective(String value) {
        when(settings.get(PaymentsRegionSettings.MODE))
                .thenReturn(new SettingRead<>(value, value, SettingSource.STORED));
    }

    private static SettingDefinition<String> declaredFor(String configured) {
        return new PaymentsRegionSettings().paymentsRegionModeSetting(
                new PaymentsRegionProperties(configured, null, null));
    }

    @Test
    void theEffectiveValueIsTheMode() {
        effective("enforce");
        assertThat(source.current()).isEqualTo(PaymentsRegionGateMode.ENFORCE);

        effective("off");
        assertThat(source.current()).isEqualTo(PaymentsRegionGateMode.OFF);

        effective("shadow");
        assertThat(source.current()).isEqualTo(PaymentsRegionGateMode.SHADOW);
    }

    @Test
    void aStoredChangeBetweenTwoReadsIsSeenByTheSecond() {
        effective("enforce");
        PaymentsRegionGateMode first = source.current();

        effective("off");

        assertThat(first).isEqualTo(PaymentsRegionGateMode.ENFORCE);
        assertThat(source.current()).isEqualTo(PaymentsRegionGateMode.OFF);
    }

    @Test
    void aBadConfiguredValueReadsAsShadowAndWarnsOnceAcrossReads() {
        SettingDefinition<String> definition = declaredFor("enforc");

        assertThat(definition.configuredValue()).isEqualTo("shadow");
        assertThat(definition.configuredValue()).isEqualTo("shadow");
        assertThat(definition.configuredValue()).isEqualTo("shadow");

        assertThat(logged.list).hasSize(1).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("enforc");
        });
    }

    @Test
    void theConfiguredValuesOfTheModeAreReadAsTheyAre() {
        assertThat(declaredFor("enforce").configuredValue()).isEqualTo("enforce");
        assertThat(declaredFor("off").configuredValue()).isEqualTo("off");
        assertThat(declaredFor("OFF").configuredValue()).isEqualTo("shadow");
        assertThat(declaredFor("").configuredValue()).isEqualTo("shadow");
        assertThat(declaredFor(null).configuredValue()).isEqualTo("shadow");
        assertThat(logged.list).hasSize(1);
    }

    @Test
    void theKeyIsAPaymentOpsEnumDefaultingToShadow() {
        SettingDefinition<String> definition = declaredFor("shadow");

        assertThat(definition.name()).isEqualTo("payment.region_gate.mode");
        assertThat(definition.key()).isEqualTo(PaymentsRegionSettings.MODE);
        assertThat(definition.scope()).isEqualTo(SettingScope.PAYMENT_OPS);
        assertThat(definition.codeDefault()).isEqualTo("shadow");
        assertThat(definition.options()).containsExactly("off", "shadow", "enforce");
    }

    @Test
    void theKeyIsADangerousRegionSetting() {
        PaymentSettingMeta meta = new PaymentsRegionSettings().paymentsRegionModeMeta();

        assertThat(meta).isEqualTo(new PaymentSettingMeta("payment.region_gate.mode", PaymentSettingGroup.REGION, true));
    }
}
