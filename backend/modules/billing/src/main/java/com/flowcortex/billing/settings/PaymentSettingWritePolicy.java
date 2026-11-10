package com.flowcortex.billing.settings;

import java.util.Optional;

/** The arbiter for a change request, first match wins: not served, then a missing reason, then proceed. */
final class PaymentSettingWritePolicy {

    private PaymentSettingWritePolicy() {
    }

    /** {@code served} is empty when the console does not serve the key; {@code rawReason} is as sent. */
    static WriteVerdict decide(Optional<PaymentSettingMeta> served, String rawReason) {
        if (served.isEmpty()) {
            return new WriteVerdict.Unknown();
        }
        String reason = rawReason == null || rawReason.isBlank() ? null : rawReason.strip();
        if (served.get().dangerous() && reason == null) {
            return new WriteVerdict.ReasonRequired();
        }
        return new WriteVerdict.Proceed(reason);
    }
}
