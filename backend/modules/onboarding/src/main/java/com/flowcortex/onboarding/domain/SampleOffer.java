package com.flowcortex.onboarding.domain;

import java.util.Optional;

/** Whether the sample is shown to members and, when not, the reason code. */
public record SampleOffer(boolean shown, String reason) {

    public static final String ONBOARDING_OFF = "onboarding_off";

    /** An eligibility reason wins over the switch; {@code onboarding_off} is never a refusal. */
    public static SampleOffer of(Optional<IneligibleReason> verdict, boolean switchOn) {
        if (verdict.isPresent()) {
            return new SampleOffer(false, verdict.get().code());
        }
        return switchOn ? new SampleOffer(true, null) : new SampleOffer(false, ONBOARDING_OFF);
    }
}
