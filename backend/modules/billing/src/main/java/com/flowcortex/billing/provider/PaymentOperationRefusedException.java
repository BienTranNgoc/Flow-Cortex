package com.flowcortex.billing.provider;

/** An operation refused before anything was signed or sent; the provider never saw it. */
public class PaymentOperationRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the operation was refused. */
    public enum Reason {
        AMOUNT_OUTSIDE_PROVIDER_RANGE("amount_outside_provider_range"),
        INSTRUMENT_NOT_OFFERED("instrument_not_offered"),
        INVALID_REFERENCE("invalid_reference"),
        INVALID_DESCRIPTION("invalid_description"),
        SIGNING_FAILED("signing_failed");

        private final String value;

        Reason(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Reason reason;

    public PaymentOperationRefusedException(Reason reason) {
        this(reason, null);
    }

    public PaymentOperationRefusedException(Reason reason, Throwable cause) {
        super("payment operation refused before sending: " + reason.value(), cause);
        this.reason = reason;
    }

    /** Names the endpoint and field at fault; never a value. */
    public PaymentOperationRefusedException(Reason reason, String endpoint, String field) {
        super("payment operation refused before sending: " + reason.value() + " (" + endpoint + "." + field + ")");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
