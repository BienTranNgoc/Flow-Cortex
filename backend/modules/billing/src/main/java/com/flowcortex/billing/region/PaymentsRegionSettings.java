package com.flowcortex.billing.region;

import com.flowcortex.billing.settings.PaymentSettingGroup;
import com.flowcortex.billing.settings.PaymentSettingMeta;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingScope;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The region gate's runtime setting: its mode, an enum the payment console changes. The configured layer is
 * {@code flowcortex.billing.region-gate.mode}, parsed once so a bad value warns at startup and not per request.
 */
@Configuration(proxyBeanMethods = false)
class PaymentsRegionSettings {

    static final SettingKey<String> MODE = SettingKey.enumKey("payment.region_gate.mode");

    private static final List<String> OPTIONS = Arrays.stream(PaymentsRegionGateMode.values())
            .map(PaymentsRegionGateMode::value)
            .toList();

    @Bean
    SettingDefinition<String> paymentsRegionModeSetting(PaymentsRegionProperties properties) {
        String configured = PaymentsRegionGateMode.parse(properties.mode()).value();
        return SettingDefinition.enumKey(MODE.name(), OPTIONS, PaymentsRegionGateMode.SHADOW.value(),
                () -> configured).inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    PaymentSettingMeta paymentsRegionModeMeta() {
        return new PaymentSettingMeta(MODE.name(), PaymentSettingGroup.REGION, true);
    }
}
