package com.flowcortex.credit.api;

import com.flowcortex.shared.web.LocalizedMessage;

/** A run refused before it started because the member cannot pay for it; rendered as 402 {@code INSUFFICIENT_CREDITS}. */
public class InsufficientCreditsException extends RuntimeException {

    public static final String CODE = "INSUFFICIENT_CREDITS";

    private final long balance;
    private final long required;

    public InsufficientCreditsException(long balance, long required) {
        super(detail(balance, required).toString());
        this.balance = balance;
        this.required = required;
    }

    private static LocalizedMessage detail(long balance, long required) {
        return new LocalizedMessage("credits.insufficient", balance, required, Math.max(required - balance, 0));
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return detail(balance, required);
    }

    public long balance() {
        return balance;
    }

    public long required() {
        return required;
    }

    public long shortfall() {
        return Math.max(required - balance, 0);
    }
}
