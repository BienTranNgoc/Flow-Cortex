package com.flowcortex.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OnboardingTracksTest {

    @Test
    void trackFollowsTheFirstIndustry() {
        Recommendation result = OnboardingTracks.of(List.of("finance", "hr"), "team");

        assertThat(result.industry()).isEqualTo("finance");
        assertThat(result.track()).isEqualTo("finance");
        assertThat(result.usageType()).isEqualTo("team");
    }

    @Test
    void noIndustryGivesOther() {
        assertThat(OnboardingTracks.of(null, null).industry()).isEqualTo("other");
        assertThat(OnboardingTracks.of(List.of(), null).track()).isEqualTo("other");
    }

    @Test
    void usageTypeDefaultsToPersonal() {
        assertThat(OnboardingTracks.of(List.of("tech"), null).usageType()).isEqualTo("personal");
    }

    @Test
    void everyIndustryHasATrack() {
        for (String industry : OnboardingVocabulary.INDUSTRIES) {
            assertThat(OnboardingTracks.of(List.of(industry), null).track()).isNotBlank();
        }
    }
}
