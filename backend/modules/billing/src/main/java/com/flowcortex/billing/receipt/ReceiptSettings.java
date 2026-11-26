package com.flowcortex.billing.receipt;

import com.flowcortex.billing.settings.PaymentSettingGroup;
import com.flowcortex.billing.settings.PaymentSettingMeta;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The receipt mail's runtime setting: on by default, configured through
 * {@code flowcortex.billing.receipt.email-enabled}, changed in the payment console. It is read when a payment
 * is collected, so turning it off later does not cancel a row already written.
 */
@Configuration(proxyBeanMethods = false)
public class ReceiptSettings {

    public static final SettingKey<Boolean> EMAIL_ENABLED = SettingKey.switchKey("payment.receipt.email_enabled");

    @Bean
    SettingDefinition<Boolean> paymentReceiptEmailSetting(ReceiptProperties properties) {
        return SettingDefinition.switchKey(EMAIL_ENABLED.name(), true, properties::emailEnabled)
                .inScope(SettingScope.PAYMENT_OPS);
    }

    @Bean
    PaymentSettingMeta paymentReceiptEmailMeta() {
        return new PaymentSettingMeta(EMAIL_ENABLED.name(), PaymentSettingGroup.RECEIPT, false);
    }
}
