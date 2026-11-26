package com.flowcortex.billing.refund;

import com.flowcortex.billing.settings.PaymentSettingGroup;
import com.flowcortex.billing.settings.PaymentSettingMeta;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The refund settings the payment console changes. The two customer-refund switches are read only by the
 * change-of-mind read (the plan-page offer), never by the eligibility, request or cancel routes. A failed read
 * turns a switch off, so the turn limit is advisory while a person decides every request.
 */
@Configuration(proxyBeanMethods = false)
public class RefundSettings {

    public static final SettingKey<Boolean> SURFACE_ENABLED = SettingKey.switchKey("payment.customer_refund.surface_enabled");
    public static final SettingKey<Boolean> REFUND_ENABLED = SettingKey.switchKey("payment.customer_refund.enabled");
    public static final SettingKey<Boolean> LIMIT_ENABLED = SettingKey.switchKey("payment.change_of_mind.limit.enabled");
    public static final SettingKey<Integer> LIMIT_USES = SettingKey.integerKey("payment.change_of_mind.limit.uses");
    public static final SettingKey<Integer> LIMIT_WINDOW_MONTHS =
            SettingKey.integerKey("payment.change_of_mind.limit.window_months");

    private static final int DEFAULT_USES = 2;
    private static final int DEFAULT_WINDOW_MONTHS = 6;

    @Bean
    SettingDefinition<Boolean> customerRefundSurfaceSetting() {
        return SettingDefinition.switchKey(SURFACE_ENABLED.name(), true, () -> null)
                .inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    SettingDefinition<Boolean> customerRefundSetting() {
        return SettingDefinition.switchKey(REFUND_ENABLED.name(), true, () -> null)
                .inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    SettingDefinition<Boolean> changeOfMindLimitEnabledSetting() {
        return SettingDefinition.switchKey(LIMIT_ENABLED.name(), true, () -> null)
                .inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    SettingDefinition<Integer> changeOfMindLimitUsesSetting() {
        return SettingDefinition.integerKey(LIMIT_USES.name(), 1, 20, () -> DEFAULT_USES)
                .inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    SettingDefinition<Integer> changeOfMindLimitWindowMonthsSetting() {
        return SettingDefinition.integerKey(LIMIT_WINDOW_MONTHS.name(), 1, 24, () -> DEFAULT_WINDOW_MONTHS)
                .inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    PaymentSettingMeta customerRefundSurfaceMeta() {
        return new PaymentSettingMeta(SURFACE_ENABLED.name(), PaymentSettingGroup.REFUND, true);
    }

    @Bean
    PaymentSettingMeta customerRefundMeta() {
        return new PaymentSettingMeta(REFUND_ENABLED.name(), PaymentSettingGroup.REFUND, true);
    }

    @Bean
    PaymentSettingMeta changeOfMindLimitEnabledMeta() {
        return new PaymentSettingMeta(LIMIT_ENABLED.name(), PaymentSettingGroup.REFUND, false);
    }

    @Bean
    PaymentSettingMeta changeOfMindLimitUsesMeta() {
        return new PaymentSettingMeta(LIMIT_USES.name(), PaymentSettingGroup.REFUND, false);
    }

    @Bean
    PaymentSettingMeta changeOfMindLimitWindowMonthsMeta() {
        return new PaymentSettingMeta(LIMIT_WINDOW_MONTHS.name(), PaymentSettingGroup.REFUND, false);
    }
}
