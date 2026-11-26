package com.flowcortex.settings.api;

/**
 * Which admin surface a key belongs to. The general settings API serves {@link #GENERAL} keys only
 * and treats a key of any other scope as undeclared. {@link #PAYMENT_OPS} keys are served by the payment
 * console and {@link #RBAC_RUNTIME} keys by the super-admin role-edit route.
 */
public enum SettingScope {
    GENERAL,
    PAYMENT_OPS,
    RBAC_RUNTIME
}
