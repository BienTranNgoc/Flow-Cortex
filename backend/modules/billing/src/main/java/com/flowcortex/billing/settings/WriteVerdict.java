package com.flowcortex.billing.settings;

/** What {@link PaymentSettingWritePolicy} decides about a change request before the registry sees the value. */
sealed interface WriteVerdict {

    /** The key is not served by the payment console. */
    record Unknown() implements WriteVerdict {
    }

    /** The key is dangerous and the request carries no reason. */
    record ReasonRequired() implements WriteVerdict {
    }

    /** Write it; {@code reason} is trimmed and null when none was given. */
    record Proceed(String reason) implements WriteVerdict {
    }
}
