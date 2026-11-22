package com.flowcortex.billing.provider;

/** The provider's verdict on a refund query, reduced to the three answers a caller can act on. */
public sealed interface RefundQueryOutcome {

    /** The refund went through. */
    record Settled(String providerTransactionId, long amount, String resultCode) implements RefundQueryOutcome {
    }

    /** The provider is still processing it; ask again later. */
    record StillPending(String resultCode) implements RefundQueryOutcome {
    }

    /** Any other answer: not a verdict either way. */
    record Unknown(String resultCode) implements RefundQueryOutcome {
    }
}
