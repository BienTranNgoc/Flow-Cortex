package com.flowcortex.billing.payment;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * What one interaction with a payment provider produced, as the adapter normalised it
 * ({@code contracts/payment-provider-event.schema.json}). A null field means the provider stated nothing;
 * the result code and message are the provider's own words, never a status of ours.
 */
public record PaymentProviderEvent(
        @NotNull @Pattern(regexp = "1") String schemaVersion,
        @NotNull @Pattern(regexp = "^[a-z][a-z0-9_]{1,31}$") String provider,
        @NotNull Kind kind,
        @NotNull @Valid Reference reference,
        @Size(max = 32) String resultCode,
        @Size(max = 512) String resultMessage,
        @PositiveOrZero Long amount,
        Instant providerTimestamp,
        @Size(max = 32) String providerPayType,
        @NotNull Instant receivedAt) {

    /** Which interaction produced the event. */
    public enum Kind {
        PAYMENT_INITIATION("payment_initiation"),
        MANDATE_ENROLMENT_EXCHANGE("mandate_enrolment_exchange"),
        MANDATE_ENROLMENT_TOKEN_QUERY("mandate_enrolment_token_query"),
        MANDATE_CHARGE("mandate_charge"),
        MANDATE_MANAGEMENT("mandate_management"),
        TRANSACTION_QUERY("transaction_query"),
        REFUND("refund"),
        REFUND_QUERY("refund_query"),
        INBOUND_PAYMENT_CALLBACK("inbound_payment_callback"),
        INBOUND_MANDATE_CALLBACK("inbound_mandate_callback");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /** Both sides' identifiers: ours (order and idempotency key) and the provider's transaction id once issued. */
    public record Reference(
            @NotNull @Pattern(regexp = PaymentOperationRefs.ORDER_REF_PATTERN) String operationRef,
            @NotNull @Pattern(regexp = PaymentOperationRefs.UUID_PATTERN) String requestRef,
            @Size(max = 64) String providerTransactionId) {
    }
}
