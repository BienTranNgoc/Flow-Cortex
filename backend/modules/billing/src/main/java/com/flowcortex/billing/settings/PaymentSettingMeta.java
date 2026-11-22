package com.flowcortex.billing.settings;

/**
 * What the payment console adds to a registry key of scope {@code PAYMENT_OPS}: its section and whether a
 * change needs a reason. One bean per key, declared by the module that owns the behaviour.
 */
public record PaymentSettingMeta(String key, PaymentSettingGroup group, boolean dangerous) {
}
