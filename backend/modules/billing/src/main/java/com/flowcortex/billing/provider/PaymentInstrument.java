package com.flowcortex.billing.provider;

import java.util.Optional;

/** How a payer can pay at a provider. The wire value is what configuration and requests carry. */
public enum PaymentInstrument {
    WALLET("wallet"),
    DOMESTIC_CARD("domestic_card");

    private final String wire;

    PaymentInstrument(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<PaymentInstrument> fromWire(String value) {
        for (PaymentInstrument instrument : values()) {
            if (instrument.wire.equals(value)) {
                return Optional.of(instrument);
            }
        }
        return Optional.empty();
    }
}
