package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.provider.PaymentInstrument;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpenPaymentTest {

    private static final UUID MEMBER = UUID.fromString("7d0c1b2e-3f4a-4b5c-8d6e-7f8091a2b3c4");

    private static OpenPayment topUp() {
        return new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp 10.000 credit", 50_000L, "momo", "MOMOBKUN20180529",
                Optional.of(PaymentInstrument.WALLET));
    }

    private static void assertRefused(String field, Executable opening) {
        assertThatThrownBy(opening::execute)
                .isInstanceOfSatisfying(PaymentOpeningRefusedException.class,
                        refused -> assertThat(refused.field()).isEqualTo(field));
    }

    @Test
    void theValidTopUpBuildsWithEveryValueKept() {
        OpenPayment opening = topUp();

        assertThat(opening.userId()).isEqualTo(MEMBER);
        assertThat(opening.kind()).isEqualTo("topup");
        assertThat(opening.itemRef()).isEqualTo("pkg-50k");
        assertThat(opening.description()).isEqualTo("Nạp 10.000 credit");
        assertThat(opening.amountVnd()).isEqualTo(50_000L);
        assertThat(opening.provider()).isEqualTo("momo");
        assertThat(opening.merchantCode()).isEqualTo("MOMOBKUN20180529");
        assertThat(opening.instrument()).contains(PaymentInstrument.WALLET);
    }

    @Test
    void aPlanPurchaseBuildsWithNoInstrument() {
        OpenPayment opening = new OpenPayment(MEMBER, "plan_purchase", "basic:monthly", "Basic", 199_000L, "momo", "M1",
                Optional.empty());

        assertThat(opening.instrument()).isEmpty();
    }

    @Test
    void anOpeningStatedWithAnAbsentInstrumentReadsAsNoInstrument() {
        assertThat(new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, "momo", "M1", null).instrument()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 999, 50_000_001, Long.MIN_VALUE, Long.MAX_VALUE})
    void anAmountOfZeroOrLessOrOutsideTheProviderRangeIsRefused(long amount) {
        assertRefused("amountVnd", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", amount, "momo", "M1",
                Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(longs = {1_000, 50_000_000})
    void anAmountAtTheProviderBoundsBuilds(long amount) {
        assertThat(new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", amount, "momo", "M1", Optional.empty()).amountVnd())
                .isEqualTo(amount);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "Nạp\ncredit", "Nạp\tcredit", "Nạp\u0000credit", "Nạp\u007fcredit", "Nạp\u0085credit"})
    void aBlankOrControlCharacterDescriptionIsRefused(String description) {
        assertRefused("description", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", description, 50_000L, "momo", "M1",
                Optional.empty()));
    }

    @Test
    void aDescriptionOfTwoHundredAndOneCharactersIsRefused() {
        assertRefused("description", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", "a".repeat(201), 50_000L, "momo",
                "M1", Optional.empty()));
    }

    @Test
    void aTwoHundredCharacterVietnameseDescriptionBuilds() {
        String description = "Nạp".repeat(66) + "ạ";

        assertThat(description).hasSize(199);
        assertThat(new OpenPayment(MEMBER, "topup", "pkg-50k", description + "ế", 50_000L, "momo", "M1", Optional.empty())
                .description()).hasSize(200);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"subscription", "refund", "TOPUP", ""})
    void aKindOutsideTheTwoIsRefused(String kind) {
        assertRefused("kind", () -> new OpenPayment(MEMBER, kind, "pkg-50k", "Nạp", 50_000L, "momo", "M1", Optional.empty()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Basic", "", "-basic", "has space", "a/b"})
    void anItemReferenceOutsideTheShapeIsRefused(String itemRef) {
        assertRefused("itemRef", () -> new OpenPayment(MEMBER, "topup", itemRef, "Nạp", 50_000L, "momo", "M1", Optional.empty()));
    }

    @Test
    void anItemReferenceOfSixtyFiveCharactersIsRefusedAndOfSixtyFourBuilds() {
        assertRefused("itemRef", () -> new OpenPayment(MEMBER, "topup", "a".repeat(65), "Nạp", 50_000L, "momo", "M1",
                Optional.empty()));
        assertThat(new OpenPayment(MEMBER, "topup", "a".repeat(64), "Nạp", 50_000L, "momo", "M1", Optional.empty()).itemRef())
                .hasSize(64);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"MoMo", "", "m", "1momo", "momo-pay"})
    void aProviderKeyOutsideTheShapeIsRefused(String provider) {
        assertRefused("provider", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, provider, "M1",
                Optional.empty()));
    }

    @Test
    void aProviderKeyOfThirtyThreeCharactersIsRefusedAndOfThirtyTwoBuilds() {
        assertRefused("provider", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, "a".repeat(33), "M1",
                Optional.empty()));
        assertThat(new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, "a".repeat(32), "M1", Optional.empty())
                .provider()).hasSize(32);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankMerchantCodeIsRefused(String merchantCode) {
        assertRefused("merchantCode", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, "momo", merchantCode,
                Optional.empty()));
    }

    @Test
    void aMerchantCodeOfSixtyFiveCharactersIsRefusedAndOfSixtyFourBuilds() {
        assertRefused("merchantCode", () -> new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, "momo",
                "M".repeat(65), Optional.empty()));
        assertThat(new OpenPayment(MEMBER, "topup", "pkg-50k", "Nạp", 50_000L, "momo", "M".repeat(64), Optional.empty())
                .merchantCode()).hasSize(64);
    }

    @Test
    void noMemberIsRefused() {
        assertRefused("userId", () -> new OpenPayment(null, "topup", "pkg-50k", "Nạp", 50_000L, "momo", "M1",
                Optional.empty()));
    }
}
