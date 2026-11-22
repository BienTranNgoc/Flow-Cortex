package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentOperationRefusedException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MomoRequestTypesTest {

    private static final List<PaymentInstrument> BOTH = List.of(PaymentInstrument.WALLET, PaymentInstrument.DOMESTIC_CARD);

    @Test
    void aChosenInstrumentMapsToItsRequestType() {
        MomoRequestTypes types = new MomoRequestTypes(BOTH);

        assertThat(types.requestType(Optional.of(PaymentInstrument.DOMESTIC_CARD))).isEqualTo("payWithATM");
        assertThat(types.requestType(Optional.of(PaymentInstrument.WALLET))).isEqualTo("captureWallet");
    }

    @Test
    void noChoiceUsesTheFirstOfferedInstrument() {
        assertThat(new MomoRequestTypes(BOTH).requestType(Optional.empty())).isEqualTo("captureWallet");
        assertThat(new MomoRequestTypes(List.of(PaymentInstrument.DOMESTIC_CARD)).requestType(Optional.empty()))
                .isEqualTo("payWithATM");
    }

    @Test
    void anInstrumentThatIsNotOfferedIsRefused() {
        MomoRequestTypes walletOnly = new MomoRequestTypes(List.of(PaymentInstrument.WALLET));

        assertThatThrownBy(() -> walletOnly.requestType(Optional.of(PaymentInstrument.DOMESTIC_CARD)))
                .isInstanceOfSatisfying(PaymentOperationRefusedException.class,
                        e -> assertThat(e.reason())
                                .isEqualTo(PaymentOperationRefusedException.Reason.INSTRUMENT_NOT_OFFERED));
    }

    @Test
    void offeredInstrumentsKeepTheConfiguredOrder() {
        assertThat(new MomoRequestTypes(List.of(PaymentInstrument.DOMESTIC_CARD, PaymentInstrument.WALLET))
                .offeredInstruments()).containsExactly(PaymentInstrument.DOMESTIC_CARD, PaymentInstrument.WALLET);
    }

    @Test
    void nothingOfferedCannotBeBuilt() {
        assertThatThrownBy(() -> new MomoRequestTypes(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
