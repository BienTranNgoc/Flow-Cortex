package com.flowcortex.billing.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentAmountLimitsTest {

    @ParameterizedTest
    @ValueSource(longs = {999, 50_000_001, 0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
    void anAmountBelowTheMinimumOrAboveTheMaximumIsOutsideTheProviderRange(long amount) {
        assertThat(PaymentAmountLimits.outsideProviderRange(amount)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {1_000, 1_001, 25_000, 50_000_000})
    void theBoundsAndEverythingBetweenThemAreInsideTheRange(long amount) {
        assertThat(PaymentAmountLimits.outsideProviderRange(amount)).isFalse();
    }
}
