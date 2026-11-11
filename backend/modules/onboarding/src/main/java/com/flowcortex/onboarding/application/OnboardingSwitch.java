package com.flowcortex.onboarding.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Onboarding journey kill switch, read once at startup; absent means off. */
@Component
class OnboardingSwitch {

    private final boolean enabled;

    OnboardingSwitch(@Value("${flowcortex.onboarding.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }
}
