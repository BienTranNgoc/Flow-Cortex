package com.flowcortex.billing.ledger;

import java.util.Optional;

/**
 * Where a payment's money stands. The wire value is what the database stores and the API returns; the
 * rank orders the lifecycle so a move to a lower rank is recognised as going backwards.
 */
public enum MoneyStatus {
    PENDING("pending", 0),
    SUCCEEDED("succeeded", 1),
    FAILED("failed", 1),
    EXPIRED("expired", 1),
    PARTIALLY_REFUNDED("partially_refunded", 2),
    REFUNDED("refunded", 3),
    DISPUTED("disputed", 4);

    private final String wire;
    private final int rank;

    MoneyStatus(String wire, int rank) {
        this.wire = wire;
        this.rank = rank;
    }

    public String wire() {
        return wire;
    }

    public int rank() {
        return rank;
    }

    /** Whether money reached us: paid, and not (wholly) taken back by a dispute. */
    public boolean isSettled() {
        return this == SUCCEEDED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }

    public static Optional<MoneyStatus> fromWire(String value) {
        for (MoneyStatus status : values()) {
            if (status.wire.equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
