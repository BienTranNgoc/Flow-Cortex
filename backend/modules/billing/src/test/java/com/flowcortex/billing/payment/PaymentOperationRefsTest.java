package com.flowcortex.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentOperationRefsTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(PaymentOperationRefs.UUID_PATTERN);

    @Test
    void aMintedOrderReferenceIsFcAndThirtyTwoLowercaseHexDigits() {
        String ref = PaymentOperationRefs.newOrderRef();

        assertThat(ref).matches("^fc_[0-9a-f]{32}$").hasSize(35);
        assertThat(PaymentOperationRefs.isOrderRef(ref)).isTrue();
    }

    @Test
    void twoMintedOrderReferencesDiffer() {
        assertThat(PaymentOperationRefs.newOrderRef()).isNotEqualTo(PaymentOperationRefs.newOrderRef());
    }

    @Test
    void aMintedRequestReferenceIsACanonicalUuid() {
        String ref = PaymentOperationRefs.newRequestRef();

        assertThat(UUID.fromString(ref)).hasToString(ref);
        assertThat(UUID_PATTERN.matcher(ref).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "xo_0123456789abcdef0123456789abcdef",
            "fc_0123456789ABCDEF0123456789ABCDEF",
            "fc_0123456789abcdef0123456789abcde",
            "fc_0123456789abcdef0123456789abcdef0",
            "",
            "fc_",
    })
    void aReferenceNotOfOurShapeIsNotAnOrderReference(String value) {
        assertThat(PaymentOperationRefs.isOrderRef(value)).isFalse();
    }

    @Test
    void nullAndAnOverLongStringAreNotOrderReferences() {
        assertThat(PaymentOperationRefs.isOrderRef(null)).isFalse();
        assertThat(PaymentOperationRefs.isOrderRef("fc_" + "a".repeat(62))).isFalse();
        assertThat(PaymentOperationRefs.isOrderRef("a".repeat(65))).isFalse();
    }

    @Test
    void theByteLengthIsCheckedNotTheCharacterCount() {
        // 33 two-byte characters are 66 bytes: over the ceiling although only 33 characters long.
        assertThat(PaymentOperationRefs.isOrderRef("é".repeat(33))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11",
            "5F0C7C1E-8F7E-4A53-9A38-2F6D6F3F9C11",
    })
    void theUuidPatternAcceptsEitherCase(String value) {
        assertThat(UUID_PATTERN.matcher(value).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1-1-1-1-1",
            "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c1",
            "{5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11}",
            "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11\n",
            "",
    })
    void theUuidPatternRefusesAnythingNotCanonical(String value) {
        assertThat(UUID_PATTERN.matcher(value).matches()).isFalse();
    }
}
