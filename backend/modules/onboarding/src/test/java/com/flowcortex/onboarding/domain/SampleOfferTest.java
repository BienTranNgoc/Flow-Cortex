package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SampleOfferTest {

    @Test
    void switchOffReportsOnboardingOff() {
        SampleOffer offer = SampleOffer.of(Optional.empty(), false);
        assertThat(offer.shown()).isFalse();
        assertThat(offer.reason()).isEqualTo("onboarding_off");
    }

    @Test
    void ineligibleReasonWinsOverSwitchOff() {
        SampleOffer offer = SampleOffer.of(Optional.of(IneligibleReason.PRIVATE), false);
        assertThat(offer.shown()).isFalse();
        assertThat(offer.reason()).isEqualTo("private");
    }

    @Test
    void switchOnAndEligibleIsShown() {
        SampleOffer offer = SampleOffer.of(Optional.empty(), true);
        assertThat(offer.shown()).isTrue();
        assertThat(offer.reason()).isNull();
    }
}
