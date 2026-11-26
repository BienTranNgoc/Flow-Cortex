package com.flowcortex.onboarding.application;

import com.flowcortex.onboarding.domain.StoredSample;
import com.flowcortex.onboarding.infrastructure.OnboardingSampleRepository;
import com.flowcortex.workflow.api.DiscoveryExclusions;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The onboarding sample is run by every new member, so its co-runs say nothing and recommending it to everyone would
 * crowd out the rest; discovery leaves it out of the recommended and similar lists.
 */
@Component
class OnboardingSampleDiscoveryExclusions implements DiscoveryExclusions {

    private final OnboardingSampleRepository sample;

    OnboardingSampleDiscoveryExclusions(OnboardingSampleRepository sample) {
        this.sample = sample;
    }

    /** The configured sample workflow, empty while none is configured. */
    @Override
    public Set<UUID> workflowIds() {
        StoredSample configured = sample.read().sample();
        return configured == null ? Set.of() : Set.of(configured.workflowId());
    }
}
