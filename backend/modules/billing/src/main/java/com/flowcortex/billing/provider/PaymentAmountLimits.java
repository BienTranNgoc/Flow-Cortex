package com.flowcortex.billing.provider;

/** The charge range the payment provider accepts, in đồng. */
public final class PaymentAmountLimits {

    public static final long MIN_CHARGE_VND = 1_000L;

    public static final long MAX_CHARGE_VND = 50_000_000L;

    private PaymentAmountLimits() {
    }

    public static boolean outsideProviderRange(long amountVnd) {
        return amountVnd < MIN_CHARGE_VND || amountVnd > MAX_CHARGE_VND;
    }
}
