package com.flowcortex.settings.api;

/**
 * Which admin surface a key belongs to. The general settings API serves {@link #GENERAL} keys only
 * and treats a key of any other scope as undeclared.
 */
public enum SettingScope {
    GENERAL,
    PAYMENT_OPS
}
