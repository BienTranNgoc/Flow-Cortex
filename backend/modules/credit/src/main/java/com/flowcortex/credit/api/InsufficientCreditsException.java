package com.flowcortex.credit.api;

/** A run refused before it started because the member cannot pay for it; rendered as 402 {@code INSUFFICIENT_CREDITS}. */
public class InsufficientCreditsException extends RuntimeException {

    public static final String CODE = "INSUFFICIENT_CREDITS";

    private final long balance;
    private final long required;

    public InsufficientCreditsException(long balance, long required) {
        super("Not enough credits: you have " + balance + ", this run needs " + required + " (short "
                + Math.max(required - balance, 0) + ").");
        this.balance = balance;
        this.required = required;
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
