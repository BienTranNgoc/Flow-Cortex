package com.flowcortex.billing.momo;

import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentOperationRefusedException;
import java.util.List;
import java.util.Optional;

/** Turns the instrument a payer chose into the MoMo request type that opens its page, among those on offer. */
final class MomoRequestTypes {

    private final List<PaymentInstrument> offered;

    MomoRequestTypes(List<PaymentInstrument> offered) {
        if (offered == null || offered.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument must be offered");
        }
        this.offered = List.copyOf(offered);
    }

    /** The instruments on offer, in the configured order. */
    List<PaymentInstrument> offeredInstruments() {
        return offered;
    }

    /** With no choice, the first instrument on offer; a choice that is not on offer is refused. */
    String requestType(Optional<PaymentInstrument> chosen) {
        PaymentInstrument instrument = chosen.orElse(offered.get(0));
        if (!offered.contains(instrument)) {
            throw new PaymentOperationRefusedException(PaymentOperationRefusedException.Reason.INSTRUMENT_NOT_OFFERED);
        }
        return switch (instrument) {
            case WALLET -> "captureWallet";
            case DOMESTIC_CARD -> "payWithATM";
        };
    }
}
