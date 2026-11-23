package com.flowcortex.billing.ledger;

/** A payment was not opened because one of its values is not acceptable; nothing was stored. */
public class PaymentOpeningRefusedException extends RuntimeException {

    private final String field;

    public PaymentOpeningRefusedException(String field) {
        super("Payment opening refused: invalid " + field);
        this.field = field;
    }

    /** The name of the refused value, as the opening states it (for example {@code amountVnd}). */
    public String field() {
        return field;
    }
}
