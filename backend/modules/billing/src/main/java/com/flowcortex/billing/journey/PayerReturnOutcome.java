package com.flowcortex.billing.journey;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What the return page concluded, from the fixed set of words it may report. */
public enum PayerReturnOutcome {
    /** The record says the money moved and matched. */
    PAID("paid"),
    /** The record says the payment failed. */
    NOT_PAID("not_paid"),
    /** Refunded, partially refunded or under dispute. */
    SETTLED_OTHER("settled_other"),
    /** Not settled yet when the payer arrived. */
    WAITING("waiting"),
    /** The page stopped watching an arrival that had still not settled. */
    STOPPED_WAITING("stopped_waiting"),
    /** The account holds no payment under that reference. */
    MISSING("missing"),
    /** The record could not be read at all. */
    UNREADABLE("unreadable"),
    /** The page was opened with no order reference to look up. */
    UNIDENTIFIED("unidentified");

    private static final Map<String, PayerReturnOutcome> BY_VALUE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(PayerReturnOutcome::value, Function.identity()));

    private final String value;

    PayerReturnOutcome(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    static Optional<PayerReturnOutcome> fromValue(String value) {
        return Optional.ofNullable(BY_VALUE.get(value));
    }
}
